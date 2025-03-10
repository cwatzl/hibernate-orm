/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.dialect.temptable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.relational.Exportable;
import org.hibernate.boot.model.relational.QualifiedNameParser;
import org.hibernate.boot.model.relational.QualifiedTableName;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.Size;
import org.hibernate.id.OptimizableGenerator;
import org.hibernate.id.enhanced.Optimizer;
import org.hibernate.internal.CoreLogging;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.internal.util.collections.ArrayHelper;
import org.hibernate.mapping.Collection;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Contributable;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Selectable;
import org.hibernate.mapping.SimpleValue;
import org.hibernate.mapping.Value;
import org.hibernate.metamodel.mapping.EntityDiscriminatorMapping;
import org.hibernate.metamodel.mapping.EntityIdentifierMapping;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.mapping.ForeignKeyDescriptor;
import org.hibernate.metamodel.mapping.JdbcMapping;
import org.hibernate.metamodel.mapping.ModelPart;
import org.hibernate.metamodel.mapping.PluralAttributeMapping;
import org.hibernate.metamodel.spi.RuntimeModelCreationContext;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.persister.entity.SingleTableEntityPersister;
import org.hibernate.generator.Generator;
import org.hibernate.type.BasicType;
import org.hibernate.type.StandardBasicTypes;
import org.hibernate.type.spi.TypeConfiguration;

/**
 * @author Steve Ebersole
 * @author Christian Beikov
 */
public class TemporaryTable implements Exportable, Contributable {

	public static final String ID_TABLE_PREFIX = "HT_";
	public static final String ENTITY_TABLE_PREFIX = "HTE_";
	public static final String DEFAULT_ALIAS = "temptable_";
	public static final String ENTITY_TABLE_IDENTITY_COLUMN = "HTE_IDENTITY";

	private static final CoreMessageLogger LOG = CoreLogging.messageLogger( TemporaryTable.class );

	private final EntityMappingType entityDescriptor;
	private final String qualifiedTableName;

	private final TemporaryTableSessionUidColumn sessionUidColumn;
	private final List<TemporaryTableColumn> columns;
	private final List<TemporaryTableColumn> columnsForExport;

	private final Dialect dialect;

	private TemporaryTable(
			EntityMappingType entityDescriptor,
			Function<String, String> temporaryTableNameAdjuster,
			Dialect dialect,
			RuntimeModelCreationContext creationContext,
			Function<TemporaryTable, List<TemporaryTableColumn>> columnInitializer) {
		this.entityDescriptor = entityDescriptor;
		final EntityPersister entityPersister = entityDescriptor.getEntityPersister();
		final EntityPersister rootEntityPersister = entityDescriptor.getRootEntityDescriptor().getEntityPersister();
		final String persisterQuerySpace = entityPersister.getSynchronizedQuerySpaces()[0];
		final QualifiedNameParser.NameParts nameParts = QualifiedNameParser.INSTANCE.parse( persisterQuerySpace );
		// The table name might be a sub-query, which is inappropriate for a temporary table name
		final String tableBaseName;
		if ( rootEntityPersister != entityPersister && rootEntityPersister instanceof SingleTableEntityPersister ) {
			// In this case, the descriptor is a subclass of a single table inheritance.
			// To avoid name collisions, we suffix the table name with the subclass number
			tableBaseName = nameParts.getObjectName().getText() + ArrayHelper.indexOf(
					( (SingleTableEntityPersister) rootEntityPersister ).getSubclassClosure(),
					entityPersister.getEntityName()
			);
		}
		else {
			tableBaseName = nameParts.getObjectName().getText();
		}
		final QualifiedNameParser.NameParts adjustedNameParts = QualifiedNameParser.INSTANCE.parse(
				temporaryTableNameAdjuster.apply( tableBaseName )
		);
		final String temporaryTableName = adjustedNameParts.getObjectName().getText();
		final Identifier tableNameIdentifier;
		if ( temporaryTableName.length() > dialect.getMaxIdentifierLength() ) {
			tableNameIdentifier = new Identifier(
					temporaryTableName.substring( 0, dialect.getMaxIdentifierLength() ),
					nameParts.getObjectName().isQuoted()
			);
		}
		else {
			tableNameIdentifier = new Identifier( temporaryTableName, nameParts.getObjectName().isQuoted() );
		}
		this.qualifiedTableName = creationContext.getSqlStringGenerationContext().format(
				new QualifiedTableName(
						adjustedNameParts.getCatalogName() != null
								? adjustedNameParts.getCatalogName()
								: nameParts.getCatalogName(),
						adjustedNameParts.getSchemaName() != null
								? adjustedNameParts.getSchemaName()
								: nameParts.getSchemaName(),
						tableNameIdentifier
				)
		);
		this.dialect = dialect;
		if ( dialect.getSupportedTemporaryTableKind() == TemporaryTableKind.PERSISTENT ) {
			final TypeConfiguration typeConfiguration = entityPersister
					.getFactory()
					.getTypeConfiguration();
			final BasicType<UUID> uuidType = typeConfiguration.getBasicTypeRegistry().resolve(
					StandardBasicTypes.UUID_CHAR
			);
			final Size size = dialect.getSizeStrategy().resolveSize(
					uuidType.getJdbcType(),
					uuidType.getJavaTypeDescriptor(),
					null,
					null,
					null
			);
			this.sessionUidColumn = new TemporaryTableSessionUidColumn(
					this,
					uuidType,
					typeConfiguration.getDdlTypeRegistry().getTypeName(
							uuidType.getJdbcType().getDdlTypeCode(),
							size
					),
					size
			);
		}
		else {
			this.sessionUidColumn = null;
		}
		final List<TemporaryTableColumn> columns = columnInitializer.apply( this );
		if ( sessionUidColumn != null ) {
			columns.add( sessionUidColumn );
		}
		this.columns = columns;

		if ( columns.size() > 1 ) {
			final ArrayList<TemporaryTableColumn> columnsForExport = new ArrayList<>( columns );
			creationContext.getBootModel().getMetadataBuildingOptions().getColumnOrderingStrategy()
					.orderTemporaryTableColumns( columnsForExport, creationContext.getMetadata() );
			this.columnsForExport = columnsForExport;
		}
		else {
			this.columnsForExport = columns;
		}
	}

	public static TemporaryTable createIdTable(
			EntityMappingType entityDescriptor,
			Function<String, String> temporaryTableNameAdjuster,
			Dialect dialect,
			RuntimeModelCreationContext runtimeModelCreationContext) {
		return new TemporaryTable(
				entityDescriptor,
				temporaryTableNameAdjuster,
				dialect,
				runtimeModelCreationContext,
				temporaryTable -> {
					final List<TemporaryTableColumn> columns = new ArrayList<>();
					final PersistentClass entityBinding = runtimeModelCreationContext.getBootModel()
							.getEntityBinding( entityDescriptor.getEntityName() );

					final Iterator<JdbcMapping> jdbcMappings = entityDescriptor.getIdentifierMapping()
							.getJdbcMappings()
							.iterator();
					for ( Column column : entityBinding.getKey().getColumns() ) {
						final JdbcMapping jdbcMapping = jdbcMappings.next();
						columns.add(
								new TemporaryTableColumn(
										temporaryTable,
										column.getText( dialect ),
										jdbcMapping,
										column.getSqlType(
												runtimeModelCreationContext.getMetadata()
										),
										column.getColumnSize(
												dialect,
												runtimeModelCreationContext.getMetadata()
										),
										column.isNullable(),
										true
								)
						);
					}

					entityDescriptor.visitSubTypeAttributeMappings(
							attribute -> {
								if ( attribute instanceof PluralAttributeMapping ) {
									final PluralAttributeMapping pluralAttribute = (PluralAttributeMapping) attribute;

									if ( pluralAttribute.getSeparateCollectionTable() != null ) {
										// Ensure that the FK target columns are available
										ForeignKeyDescriptor keyDescriptor = pluralAttribute.getKeyDescriptor();
										if ( keyDescriptor == null ) {
											// This is expected to happen when processing a
											// PostInitCallbackEntry because the callbacks
											// are not ordered. The exception is caught in
											// MappingModelCreationProcess.executePostInitCallbacks()
											// and the callback is re-queued.
											throw new IllegalStateException( "Not yet ready: " + pluralAttribute );
										}
										final ModelPart fkTarget = keyDescriptor.getTargetPart();
										if ( !( fkTarget instanceof EntityIdentifierMapping ) ) {
											final Value value = entityBinding.getSubclassProperty( pluralAttribute.getAttributeName() )
													.getValue();
											final Iterator<Selectable> columnIterator =
													( (Collection) value ).getKey().getColumnIterator();
											fkTarget.forEachSelectable(
													(columnIndex, selection) -> {
														final Selectable selectable = columnIterator.next();
														if ( selectable instanceof Column ) {
															final Column column = (Column) selectable;
															columns.add(
																	new TemporaryTableColumn(
																			temporaryTable,
																			column.getText( dialect ),
																			selection.getJdbcMapping(),
																			column.getSqlType(
																					runtimeModelCreationContext.getMetadata()
																			),
																			column.getColumnSize(
																					dialect,
																					runtimeModelCreationContext.getMetadata()
																			),
																			column.isNullable()
																	)
															);
														}
													}
											);
										}
									}
								}
							}
					);
					return columns;
				}
		);
	}

	public static TemporaryTable createEntityTable(
			EntityMappingType entityDescriptor,
			Function<String, String> temporaryTableNameAdjuster,
			Dialect dialect,
			RuntimeModelCreationContext runtimeModelCreationContext) {
		return new TemporaryTable(
				entityDescriptor,
				temporaryTableNameAdjuster,
				dialect,
				runtimeModelCreationContext,
				temporaryTable -> {
					final List<TemporaryTableColumn> columns = new ArrayList<>();
					final PersistentClass entityBinding = runtimeModelCreationContext.getBootModel()
							.getEntityBinding( entityDescriptor.getEntityName() );

					final Generator identifierGenerator = entityDescriptor.getEntityPersister().getGenerator();
					final boolean identityColumn = identifierGenerator.generatedOnExecution();
					final boolean hasOptimizer;
					if ( identityColumn ) {
						hasOptimizer = false;
						final Iterator<JdbcMapping> jdbcMappings = entityDescriptor.getIdentifierMapping()
								.getJdbcMappings()
								.iterator();
						for ( Column column : entityBinding.getKey().getColumns() ) {
							final JdbcMapping jdbcMapping = jdbcMappings.next();
							columns.add(
									new TemporaryTableColumn(
											temporaryTable,
											ENTITY_TABLE_IDENTITY_COLUMN,
											jdbcMapping,
											column.getSqlType(
													runtimeModelCreationContext.getMetadata()
											) + " " +
											dialect.getIdentityColumnSupport().getIdentityColumnString( column.getSqlTypeCode( runtimeModelCreationContext.getMetadata() ) ),
											column.getColumnSize(
													dialect,
													runtimeModelCreationContext.getMetadata()
											),
											// Always report as nullable as the identity column string usually includes the not null constraint
											true,//column.isNullable()
											true
									)
							);
						}
					}
					else {
						if ( identifierGenerator instanceof OptimizableGenerator ) {
							final Optimizer optimizer = ( (OptimizableGenerator) identifierGenerator ).getOptimizer();
							hasOptimizer = optimizer != null && optimizer.getIncrementSize() > 1;
						}
						else {
							hasOptimizer = false;
						}
					}
					final Iterator<JdbcMapping> jdbcMappings = entityDescriptor.getIdentifierMapping()
							.getJdbcMappings()
							.iterator();
					for ( Column column : entityBinding.getKey().getColumns() ) {
						final JdbcMapping jdbcMapping = jdbcMappings.next();
						columns.add(
								new TemporaryTableColumn(
										temporaryTable,
										column.getText( dialect ),
										jdbcMapping,
										column.getSqlType(
												runtimeModelCreationContext.getMetadata()
										),
										column.getColumnSize(
												dialect,
												runtimeModelCreationContext.getMetadata()
										),
										// We have to set the identity column after the root table insert
										column.isNullable() || identityColumn || hasOptimizer,
										!identityColumn && !hasOptimizer
								)
						);
					}

					final EntityDiscriminatorMapping discriminatorMapping = entityDescriptor.getDiscriminatorMapping();
					if ( entityBinding.getDiscriminator() != null && !discriminatorMapping.isFormula() ) {
						final Column discriminator = entityBinding.getDiscriminator().getColumns().get(0);
						columns.add(
								new TemporaryTableColumn(
										temporaryTable,
										discriminator.getText( dialect ),
										discriminatorMapping.getJdbcMapping(),
										discriminator.getSqlType(
												runtimeModelCreationContext.getMetadata()
										),
										discriminator.getColumnSize(
												dialect,
												runtimeModelCreationContext.getMetadata()
										),
										// We have to set the identity column after the root table insert
										discriminator.isNullable()
								)
						);
					}

					// Collect all columns for all entity subtype attributes
					entityDescriptor.visitSubTypeAttributeMappings(
							attribute -> {
								if ( !( attribute instanceof PluralAttributeMapping ) ) {
									final SimpleValue value = (SimpleValue) entityBinding.getSubclassProperty( attribute.getAttributeName() )
											.getValue();
									final Iterator<Selectable> columnIterator = value.getConstraintColumnIterator();
									attribute.forEachSelectable(
											(columnIndex, selection) -> {
												final Selectable selectable = columnIterator.next();
												if ( selectable instanceof Column ) {
													final Column column = (Column) selectable;
													columns.add(
															new TemporaryTableColumn(
																	temporaryTable,
																	selectable.getText( dialect ),
																	selection.getJdbcMapping(),
																	column.getSqlType(
																			runtimeModelCreationContext.getMetadata()
																	),
																	column.getColumnSize(
																			dialect,
																			runtimeModelCreationContext.getMetadata()
																	),
																	// Treat regular temporary table columns as nullable for simplicity
																	true
															)
													);
												}
											}
									);
								}
							}
					);
					if ( hasOptimizer ) {
						final TypeConfiguration typeConfiguration = runtimeModelCreationContext.getTypeConfiguration();
						// We add a special row number column that we can use to identify and join rows
						final BasicType<Integer> integerBasicType = typeConfiguration.getBasicTypeForJavaType( Integer.class );
						final String rowNumberType;
						if ( dialect.supportsWindowFunctions() ) {
							rowNumberType = typeConfiguration.getDdlTypeRegistry().getTypeName(
									integerBasicType.getJdbcType().getDdlTypeCode(),
									dialect.getSizeStrategy().resolveSize(
											integerBasicType.getJdbcType(),
											integerBasicType.getJavaTypeDescriptor(),
											null,
											null,
											null
									)
							);
						}
						else if ( dialect.getIdentityColumnSupport().supportsIdentityColumns() ) {
							rowNumberType = typeConfiguration.getDdlTypeRegistry().getTypeName(
									integerBasicType.getJdbcType().getDdlTypeCode(),
									dialect.getSizeStrategy().resolveSize(
											integerBasicType.getJdbcType(),
											integerBasicType.getJavaTypeDescriptor(),
											null,
											null,
											null
									)
							) + " " + dialect.getIdentityColumnSupport()
									.getIdentityColumnString( integerBasicType.getJdbcType().getDdlTypeCode() );
						}
						else {
							LOG.multiTableInsertNotAvailable( entityBinding.getEntityName() );
							rowNumberType = typeConfiguration.getDdlTypeRegistry().getTypeName(
									integerBasicType.getJdbcType().getDdlTypeCode(),
									dialect.getSizeStrategy().resolveSize(
											integerBasicType.getJdbcType(),
											integerBasicType.getJavaTypeDescriptor(),
											null,
											null,
											null
									)
							);
						}
						columns.add(
								new TemporaryTableColumn(
										temporaryTable,
										"rn_",
										integerBasicType,
										rowNumberType,
										Size.nil(),
										false,
										true
								)
						);
					}
					return columns;
				}
		);
	}

	public EntityMappingType getEntityDescriptor() {
		return entityDescriptor;
	}

	public String getQualifiedTableName() {
		return qualifiedTableName;
	}

	public List<TemporaryTableColumn> getColumns() {
		return columns;
	}

	public List<TemporaryTableColumn> getColumnsForExport() {
		return columnsForExport;
	}

	public TemporaryTableSessionUidColumn getSessionUidColumn() {
		return sessionUidColumn;
	}

	public String getTableExpression() {
		return qualifiedTableName;
	}

	@Override
	public String getContributor() {
		return entityDescriptor.getContributor();
	}

	@Override
	public String getExportIdentifier() {
		return getQualifiedTableName();
	}

	public Dialect getDialect() {
		return this.dialect;
	}
}
