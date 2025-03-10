/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html.
 */
package org.hibernate.boot.model.internal;

import java.lang.reflect.ParameterizedType;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.persistence.*;
import jakarta.persistence.NamedNativeQueries;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import org.hibernate.AnnotationException;
import org.hibernate.MappingException;
import org.hibernate.annotations.*;
import org.hibernate.annotations.common.reflection.ReflectionManager;
import org.hibernate.annotations.common.reflection.XAnnotatedElement;
import org.hibernate.annotations.common.reflection.XClass;
import org.hibernate.annotations.common.reflection.XPackage;
import org.hibernate.boot.model.IdentifierGeneratorDefinition;
import org.hibernate.boot.model.convert.spi.ConverterDescriptor;
import org.hibernate.boot.model.convert.spi.RegisteredConversion;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.classloading.spi.ClassLoaderService;
import org.hibernate.boot.spi.InFlightMetadataCollector;
import org.hibernate.boot.spi.MetadataBuildingContext;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.spi.FilterDefinition;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.internal.util.GenericsHelper;
import org.hibernate.internal.util.StringHelper;
import org.hibernate.metamodel.mapping.JdbcMapping;
import org.hibernate.type.descriptor.converter.internal.JpaAttributeConverterImpl;
import org.hibernate.resource.beans.spi.ManagedBean;
import org.hibernate.resource.beans.spi.ManagedBeanRegistry;
import org.hibernate.type.BasicType;
import org.hibernate.type.CustomType;
import org.hibernate.type.descriptor.java.BasicJavaType;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.java.spi.JavaTypeRegistry;
import org.hibernate.type.descriptor.jdbc.JdbcType;
import org.hibernate.type.descriptor.jdbc.JdbcTypeIndicators;
import org.hibernate.type.internal.ConvertedBasicTypeImpl;
import org.hibernate.type.spi.TypeConfiguration;
import org.hibernate.usertype.UserType;

import static java.util.Collections.emptyMap;
import static org.hibernate.boot.model.internal.AnnotatedClassType.ENTITY;
import static org.hibernate.boot.model.internal.GeneratorBinder.buildGenerators;
import static org.hibernate.boot.model.internal.BinderHelper.getOverridableAnnotation;
import static org.hibernate.boot.model.internal.InheritanceState.getInheritanceStateOfSuperEntity;
import static org.hibernate.boot.model.internal.InheritanceState.getSuperclassInheritanceState;
import static org.hibernate.internal.CoreLogging.messageLogger;

/**
 * Reads annotations from Java classes and produces the Hibernate configuration-time metamodel,
 * that is, the objects defined in the package {@link org.hibernate.mapping}.
 *
 * @implNote This class is stateless, unlike most of the other "binders".
 *
 * @author Emmanuel Bernard
 * @author Hardy Ferentschik
 */
public final class AnnotationBinder {
	private static final CoreMessageLogger LOG = messageLogger( AnnotationBinder.class );

	private AnnotationBinder() {}

	public static void bindDefaults(MetadataBuildingContext context) {
		final Map<?,?> defaults = context.getBootstrapContext().getReflectionManager().getDefaults();

		// id generators ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

		{
			@SuppressWarnings("unchecked")
			List<SequenceGenerator> generators = ( List<SequenceGenerator> ) defaults.get( SequenceGenerator.class );
			if ( generators != null ) {
				for ( SequenceGenerator sequenceGenerator : generators ) {
					final IdentifierGeneratorDefinition idGen = GeneratorBinder.buildIdGenerator( sequenceGenerator, context );
					if ( idGen != null ) {
						context.getMetadataCollector().addDefaultIdentifierGenerator( idGen );
					}
				}
			}
		}
		{
			@SuppressWarnings("unchecked")
			List<TableGenerator> generators = ( List<TableGenerator> ) defaults.get( TableGenerator.class );
			if ( generators != null ) {
				for ( TableGenerator tableGenerator : generators ) {
					final IdentifierGeneratorDefinition idGen = GeneratorBinder.buildIdGenerator( tableGenerator, context );
					if ( idGen != null ) {
						context.getMetadataCollector().addDefaultIdentifierGenerator( idGen );
					}
				}
			}
		}

		{
			@SuppressWarnings("unchecked")
			List<TableGenerators> generators = (List<TableGenerators>) defaults.get( TableGenerators.class );
			if ( generators != null ) {
				generators.forEach( tableGenerators -> {
					for ( TableGenerator tableGenerator : tableGenerators.value() ) {
						final IdentifierGeneratorDefinition idGen = GeneratorBinder.buildIdGenerator( tableGenerator, context );
						if ( idGen != null ) {
							context.getMetadataCollector().addDefaultIdentifierGenerator( idGen );
						}
					}
				} );
			}
		}

		{
			@SuppressWarnings("unchecked")
			List<SequenceGenerators> generators = (List<SequenceGenerators>) defaults.get( SequenceGenerators.class );
			if ( generators != null ) {
				generators.forEach( sequenceGenerators -> {
					for ( SequenceGenerator sequenceGenerator : sequenceGenerators.value() ) {
						final IdentifierGeneratorDefinition idGen = GeneratorBinder.buildIdGenerator( sequenceGenerator, context );
						if ( idGen != null ) {
							context.getMetadataCollector().addDefaultIdentifierGenerator( idGen );
						}
					}
				} );
			}
		}

		// queries ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

		{
			@SuppressWarnings("unchecked")
			List<NamedQuery> queries = ( List<NamedQuery> ) defaults.get( NamedQuery.class );
			if ( queries != null ) {
				for ( NamedQuery ann : queries ) {
					QueryBinder.bindQuery( ann, context, true );
				}
			}
		}
		{
			@SuppressWarnings("unchecked")
			List<NamedNativeQuery> nativeQueries = ( List<NamedNativeQuery> ) defaults.get( NamedNativeQuery.class );
			if ( nativeQueries != null ) {
				for ( NamedNativeQuery ann : nativeQueries ) {
					QueryBinder.bindNativeQuery( ann, context, true );
				}
			}
		}

		// result-set-mappings ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

		{
			@SuppressWarnings("unchecked")
			List<SqlResultSetMapping> mappings = ( List<SqlResultSetMapping> ) defaults.get( SqlResultSetMapping.class );
			if ( mappings != null ) {
				for ( SqlResultSetMapping annotation : mappings ) {
					QueryBinder.bindSqlResultSetMapping( annotation, context, true );
				}
			}
		}

		// stored procs ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

		{
			@SuppressWarnings("unchecked")
			final List<NamedStoredProcedureQuery> storedProcedureQueries =
					(List<NamedStoredProcedureQuery>) defaults.get( NamedStoredProcedureQuery.class );
			if ( storedProcedureQueries != null ) {
				for ( NamedStoredProcedureQuery annotation : storedProcedureQueries ) {
					bindNamedStoredProcedureQuery( annotation, context, true );
				}
			}
		}
		{
			@SuppressWarnings("unchecked")
			final List<NamedStoredProcedureQueries> storedProcedureQueries =
					(List<NamedStoredProcedureQueries>) defaults.get( NamedStoredProcedureQueries.class );
			if ( storedProcedureQueries != null ) {
				for ( NamedStoredProcedureQueries annotation : storedProcedureQueries ) {
					bindNamedStoredProcedureQueries( annotation, context, true );
				}
			}
		}
	}

	public static void bindPackage(ClassLoaderService cls, String packageName, MetadataBuildingContext context) {
		final Package pack = cls.packageForNameOrNull( packageName );
		if ( pack == null ) {
			return;
		}
		final XPackage annotatedPackage = context.getBootstrapContext().getReflectionManager().toXPackage( pack );

		handleIdGenerators( annotatedPackage, context );

		bindTypeDescriptorRegistrations( annotatedPackage, context );
		bindEmbeddableInstantiatorRegistrations( annotatedPackage, context );
		bindUserTypeRegistrations( annotatedPackage, context );
		bindCompositeUserTypeRegistrations( annotatedPackage, context );
		bindConverterRegistrations( annotatedPackage, context );

		bindGenericGenerators( annotatedPackage, context );
		bindQueries( annotatedPackage, context );
		bindFilterDefs( annotatedPackage, context );
	}

	private static void handleIdGenerators(XPackage annotatedPackage, MetadataBuildingContext context) {
		if ( annotatedPackage.isAnnotationPresent( SequenceGenerator.class ) ) {
			final SequenceGenerator sequenceGenerator = annotatedPackage.getAnnotation( SequenceGenerator.class );
			IdentifierGeneratorDefinition idGen = GeneratorBinder.buildIdGenerator( sequenceGenerator, context );
			context.getMetadataCollector().addIdentifierGenerator( idGen );
			if ( LOG.isTraceEnabled() ) {
				LOG.tracev( "Add sequence generator with name: {0}", idGen.getName() );
			}
		}
		if ( annotatedPackage.isAnnotationPresent( SequenceGenerators.class ) ) {
			final SequenceGenerators sequenceGenerators = annotatedPackage.getAnnotation( SequenceGenerators.class );
			for ( SequenceGenerator tableGenerator : sequenceGenerators.value() ) {
				context.getMetadataCollector().addIdentifierGenerator( GeneratorBinder.buildIdGenerator( tableGenerator, context ) );
			}
		}

		if ( annotatedPackage.isAnnotationPresent( TableGenerator.class ) ) {
			final TableGenerator tableGenerator = annotatedPackage.getAnnotation( TableGenerator.class );
			IdentifierGeneratorDefinition idGen = GeneratorBinder.buildIdGenerator( tableGenerator, context );
			context.getMetadataCollector().addIdentifierGenerator( idGen );
		}
		if ( annotatedPackage.isAnnotationPresent( TableGenerators.class ) ) {
			final TableGenerators tableGenerators = annotatedPackage.getAnnotation( TableGenerators.class );
			for ( TableGenerator tableGenerator : tableGenerators.value() ) {
				context.getMetadataCollector().addIdentifierGenerator( GeneratorBinder.buildIdGenerator( tableGenerator, context ) );
			}
		}
	}

	private static void bindGenericGenerators(XAnnotatedElement annotatedElement, MetadataBuildingContext context) {
		final GenericGenerator genericGenerator = annotatedElement.getAnnotation( GenericGenerator.class );
		final GenericGenerators genericGenerators = annotatedElement.getAnnotation( GenericGenerators.class );
		if ( genericGenerator != null ) {
			bindGenericGenerator( genericGenerator, context );
		}
		if ( genericGenerators != null ) {
			for ( GenericGenerator generator : genericGenerators.value() ) {
				bindGenericGenerator( generator, context );
			}
		}
	}

	private static void bindGenericGenerator(GenericGenerator def, MetadataBuildingContext context) {
		context.getMetadataCollector().addIdentifierGenerator( GeneratorBinder.buildIdGenerator( def, context ) );
	}

	private static void bindNamedJpaQueries(XAnnotatedElement annotatedElement, MetadataBuildingContext context) {
		QueryBinder.bindSqlResultSetMapping(
				annotatedElement.getAnnotation( SqlResultSetMapping.class ),
				context,
				false
		);

		QueryBinder.bindSqlResultSetMappings(
				annotatedElement.getAnnotation( SqlResultSetMappings.class ),
				context,
				false
		);

		QueryBinder.bindQuery(
				annotatedElement.getAnnotation( NamedQuery.class ),
				context,
				false
		);

		QueryBinder.bindQueries(
				annotatedElement.getAnnotation( NamedQueries.class ),
				context,
				false
		);

		QueryBinder.bindNativeQuery(
				annotatedElement.getAnnotation( NamedNativeQuery.class ),
				context,
				false
		);

		QueryBinder.bindNativeQueries(
				annotatedElement.getAnnotation( NamedNativeQueries.class ),
				context,
				false
		);
	}

	public static void bindQueries(XAnnotatedElement annotatedElement, MetadataBuildingContext context) {
		bindNamedJpaQueries( annotatedElement, context );

		QueryBinder.bindQuery(
				annotatedElement.getAnnotation( org.hibernate.annotations.NamedQuery.class ),
				context
		);

		QueryBinder.bindQueries(
				annotatedElement.getAnnotation( org.hibernate.annotations.NamedQueries.class ),
				context
		);

		QueryBinder.bindNativeQuery(
				annotatedElement.getAnnotation( org.hibernate.annotations.NamedNativeQuery.class ),
				context
		);

		QueryBinder.bindNativeQueries(
				annotatedElement.getAnnotation( org.hibernate.annotations.NamedNativeQueries.class ),
				context
		);

		// NamedStoredProcedureQuery handling ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
		bindNamedStoredProcedureQuery(
				annotatedElement.getAnnotation( NamedStoredProcedureQuery.class ),
				context,
				false
		);

		// NamedStoredProcedureQueries handling ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
		bindNamedStoredProcedureQueries(
				annotatedElement.getAnnotation( NamedStoredProcedureQueries.class ),
				context,
				false
		);
	}

	private static void bindNamedStoredProcedureQueries(
			NamedStoredProcedureQueries annotation,
			MetadataBuildingContext context,
			boolean isDefault) {
		if ( annotation != null ) {
			for ( NamedStoredProcedureQuery queryAnnotation : annotation.value() ) {
				bindNamedStoredProcedureQuery( queryAnnotation, context, isDefault );
			}
		}
	}

	private static void bindNamedStoredProcedureQuery(
			NamedStoredProcedureQuery annotation,
			MetadataBuildingContext context,
			boolean isDefault) {
		if ( annotation != null ) {
			QueryBinder.bindNamedStoredProcedureQuery( annotation, context, isDefault );
		}
	}

	/**
	 * Bind an annotated class. A subclass must be bound <em>after</em> its superclass.
	 *
	 * @param annotatedClass entity to bind as {@code XClass} instance
	 * @param inheritanceStatePerClass Metadata about the inheritance relationships for all mapped classes
	 *
	 * @throws MappingException in case there is a configuration error
	 */
	public static void bindClass(
			XClass annotatedClass,
			Map<XClass, InheritanceState> inheritanceStatePerClass,
			MetadataBuildingContext context) throws MappingException {

		detectMappedSuperclassProblems( annotatedClass );

		bindQueries( annotatedClass, context );
		handleImport( annotatedClass, context );
		bindFilterDefs( annotatedClass, context );
		bindTypeDescriptorRegistrations( annotatedClass, context );
		bindEmbeddableInstantiatorRegistrations( annotatedClass, context );
		bindUserTypeRegistrations( annotatedClass, context );
		bindCompositeUserTypeRegistrations( annotatedClass, context );
		bindConverterRegistrations( annotatedClass, context );

		// try to find class level generators
		final Map<String, IdentifierGeneratorDefinition> generators = buildGenerators( annotatedClass, context );
		if ( context.getMetadataCollector().getClassType( annotatedClass ) == ENTITY ) {
			EntityBinder.bindEntityClass( annotatedClass, inheritanceStatePerClass, generators, context );
		}
	}

	private static void handleImport(XClass annotatedClass, MetadataBuildingContext context) {
		if ( annotatedClass.isAnnotationPresent( Imported.class ) ) {
			String qualifiedName = annotatedClass.getName();
			String name = StringHelper.unqualify( qualifiedName );
			String rename = annotatedClass.getAnnotation( Imported.class ).rename();
			context.getMetadataCollector().addImport( rename.isEmpty() ? name : rename, qualifiedName );
		}
	}

	private static void detectMappedSuperclassProblems(XClass annotatedClass) {
		//@Entity and @MappedSuperclass on the same class leads to a NPE down the road
		if ( annotatedClass.isAnnotationPresent( Entity.class )
				&&  annotatedClass.isAnnotationPresent( MappedSuperclass.class ) ) {
			throw new AnnotationException( "Type '"+ annotatedClass.getName()
					+ "' is annotated both '@Entity' and '@MappedSuperclass'" );
		}

		if ( annotatedClass.isAnnotationPresent( Inheritance.class )
				&&  annotatedClass.isAnnotationPresent( MappedSuperclass.class ) ) {
			LOG.unsupportedMappedSuperclassWithEntityInheritance( annotatedClass.getName() );
		}
	}

	private static void bindTypeDescriptorRegistrations(XAnnotatedElement annotatedElement, MetadataBuildingContext context) {
		final ManagedBeanRegistry managedBeanRegistry = context.getBootstrapContext()
				.getServiceRegistry()
				.getService( ManagedBeanRegistry.class );

		final JavaTypeRegistration javaTypeRegistration = annotatedElement.getAnnotation( JavaTypeRegistration.class );
		if ( javaTypeRegistration != null ) {
			handleJavaTypeRegistration( context, managedBeanRegistry, javaTypeRegistration );
		}
		else {
			final JavaTypeRegistrations javaTypeRegistrations = annotatedElement.getAnnotation( JavaTypeRegistrations.class );
			if ( javaTypeRegistrations != null ) {
				final JavaTypeRegistration[] registrations = javaTypeRegistrations.value();
				for ( JavaTypeRegistration registration : registrations ) {
					handleJavaTypeRegistration( context, managedBeanRegistry, registration );
				}
			}
		}

		final JdbcTypeRegistration jdbcTypeRegistration = annotatedElement.getAnnotation( JdbcTypeRegistration.class );
		if ( jdbcTypeRegistration != null ) {
			handleJdbcTypeRegistration( context, managedBeanRegistry, jdbcTypeRegistration );
		}
		else {
			final JdbcTypeRegistrations jdbcTypeRegistrations = annotatedElement.getAnnotation( JdbcTypeRegistrations.class );
			if ( jdbcTypeRegistrations != null ) {
				final JdbcTypeRegistration[] registrations = jdbcTypeRegistrations.value();
				for ( JdbcTypeRegistration registration : registrations ) {
					handleJdbcTypeRegistration( context, managedBeanRegistry, registration );
				}
			}
		}

		final CollectionTypeRegistration collectionTypeRegistration =
				annotatedElement.getAnnotation( CollectionTypeRegistration.class );
		if ( collectionTypeRegistration != null ) {
			context.getMetadataCollector().addCollectionTypeRegistration( collectionTypeRegistration );
		}

		final CollectionTypeRegistrations collectionTypeRegistrations =
				annotatedElement.getAnnotation( CollectionTypeRegistrations.class );
		if ( collectionTypeRegistrations != null ) {
			for ( CollectionTypeRegistration registration : collectionTypeRegistrations.value() ) {
				context.getMetadataCollector().addCollectionTypeRegistration( registration );
			}
		}
	}

	private static void handleJdbcTypeRegistration(
			MetadataBuildingContext context,
			ManagedBeanRegistry managedBeanRegistry,
			JdbcTypeRegistration annotation) {
		final Class<? extends JdbcType> jdbcTypeClass = annotation.value();
		final JdbcType jdbcType = managedBeanRegistry.getBean( jdbcTypeClass ).getBeanInstance();
		final int typeCode = annotation.registrationCode() == Integer.MIN_VALUE
				? jdbcType.getDefaultSqlTypeCode()
				: annotation.registrationCode();
		context.getMetadataCollector().addJdbcTypeRegistration( typeCode, jdbcType );
	}

	private static void handleJavaTypeRegistration(
			MetadataBuildingContext context,
			ManagedBeanRegistry managedBeanRegistry,
			JavaTypeRegistration annotation) {
		final Class<? extends BasicJavaType<?>> jtdClass = annotation.descriptorClass();
		final BasicJavaType<?> jtd = managedBeanRegistry.getBean( jtdClass ).getBeanInstance();
		context.getMetadataCollector().addJavaTypeRegistration( annotation.javaType(), jtd );
	}

	private static void bindEmbeddableInstantiatorRegistrations(
			XAnnotatedElement annotatedElement,
			MetadataBuildingContext context) {
		final EmbeddableInstantiatorRegistration embeddableInstantiatorRegistration =
				annotatedElement.getAnnotation( EmbeddableInstantiatorRegistration.class );
		if ( embeddableInstantiatorRegistration != null ) {
			handleEmbeddableInstantiatorRegistration( context, embeddableInstantiatorRegistration );
		}
		else {
			final EmbeddableInstantiatorRegistrations embeddableInstantiatorRegistrations =
					annotatedElement.getAnnotation( EmbeddableInstantiatorRegistrations.class );
			if ( embeddableInstantiatorRegistrations != null ) {
				final EmbeddableInstantiatorRegistration[] registrations = embeddableInstantiatorRegistrations.value();
				for ( EmbeddableInstantiatorRegistration registration : registrations ) {
					handleEmbeddableInstantiatorRegistration( context, registration );
				}
			}
		}
	}

	private static void handleEmbeddableInstantiatorRegistration(
			MetadataBuildingContext context,
			EmbeddableInstantiatorRegistration annotation) {
		context.getMetadataCollector().registerEmbeddableInstantiator(
				annotation.embeddableClass(),
				annotation.instantiator()
		);
	}

	private static void bindCompositeUserTypeRegistrations(
			XAnnotatedElement annotatedElement,
			MetadataBuildingContext context) {
		final CompositeTypeRegistration compositeTypeRegistration =
				annotatedElement.getAnnotation( CompositeTypeRegistration.class );
		if ( compositeTypeRegistration != null ) {
			handleCompositeUserTypeRegistration( context, compositeTypeRegistration );
		}
		else {
			final CompositeTypeRegistrations compositeTypeRegistrations =
					annotatedElement.getAnnotation( CompositeTypeRegistrations.class );
			if ( compositeTypeRegistrations != null ) {
				final CompositeTypeRegistration[] registrations = compositeTypeRegistrations.value();
				for ( CompositeTypeRegistration registration : registrations ) {
					handleCompositeUserTypeRegistration( context, registration );
				}
			}
		}
	}

	private static void bindUserTypeRegistrations(
			XAnnotatedElement annotatedElement,
			MetadataBuildingContext context) {
		final TypeRegistration typeRegistration =
				annotatedElement.getAnnotation( TypeRegistration.class );
		if ( typeRegistration != null ) {
			handleUserTypeRegistration( context, typeRegistration );
		}
		else {
			final TypeRegistrations typeRegistrations =
					annotatedElement.getAnnotation( TypeRegistrations.class );
			if ( typeRegistrations != null ) {
				final TypeRegistration[] registrations = typeRegistrations.value();
				for ( TypeRegistration registration : registrations ) {
					handleUserTypeRegistration( context, registration );
				}
			}
		}
	}

	private static void handleUserTypeRegistration(
			MetadataBuildingContext context,
			TypeRegistration compositeTypeRegistration) {
		context.getMetadataCollector().registerUserType(
				compositeTypeRegistration.basicClass(),
				compositeTypeRegistration.userType()
		);
	}

	private static void handleCompositeUserTypeRegistration(
			MetadataBuildingContext context,
			CompositeTypeRegistration compositeTypeRegistration) {
		context.getMetadataCollector().registerCompositeUserType(
				compositeTypeRegistration.embeddableClass(),
				compositeTypeRegistration.userType()
		);
	}

	private static void bindConverterRegistrations(XAnnotatedElement container, MetadataBuildingContext context) {
		final ConverterRegistration converterRegistration = container.getAnnotation( ConverterRegistration.class );
		if ( converterRegistration != null ) {
			handleConverterRegistration( converterRegistration, context );
			return;
		}

		final ConverterRegistrations converterRegistrations = container.getAnnotation( ConverterRegistrations.class );
		if ( converterRegistrations != null ) {
			final ConverterRegistration[] registrations = converterRegistrations.value();
			for ( ConverterRegistration registration : registrations ) {
				handleConverterRegistration( registration, context );
			}
		}
	}

	private static void handleConverterRegistration(ConverterRegistration registration, MetadataBuildingContext context) {
		final InFlightMetadataCollector metadataCollector = context.getMetadataCollector();
		metadataCollector.getConverterRegistry().addRegisteredConversion(
				new RegisteredConversion(
						registration.domainType(),
						registration.converter(),
						registration.autoApply(),
						context
				)
		);
	}

	public static void bindFilterDefs(XAnnotatedElement annotatedElement, MetadataBuildingContext context) {
		final FilterDef filterDef = annotatedElement.getAnnotation( FilterDef.class );
		final FilterDefs filterDefs = getOverridableAnnotation( annotatedElement, FilterDefs.class, context );
		if ( filterDef != null ) {
			bindFilterDef( filterDef, context );
		}
		if ( filterDefs != null ) {
			for ( FilterDef def : filterDefs.value() ) {
				bindFilterDef( def, context );
			}
		}
	}

	private static void bindFilterDef(FilterDef filterDef, MetadataBuildingContext context) {
		final String name = filterDef.name();
		if ( context.getMetadataCollector().getFilterDefinition( name ) != null ) {
			throw new AnnotationException( "Multiple '@FilterDef' annotations define a filter named '" + name + "'" );
		}
		final Map<String, JdbcMapping> explicitParamJaMappings;
		if ( filterDef.parameters().length == 0 ) {
			explicitParamJaMappings = emptyMap();
		}
		else {
			explicitParamJaMappings = new HashMap<>();
			for ( ParamDef paramDef : filterDef.parameters() ) {
				final JdbcMapping jdbcMapping = resolveFilterParamType( paramDef.type(), context );
				if ( jdbcMapping == null ) {
					throw new MappingException(
							String.format(
									Locale.ROOT,
									"Unable to resolve type specified for parameter (%s) defined for @FilterDef (%s)",
									paramDef.name(),
									name
							)
					);
				}
				explicitParamJaMappings.put( paramDef.name(), jdbcMapping );
			}
		}
		final FilterDefinition filterDefinition =
				new FilterDefinition( name, filterDef.defaultCondition(), explicitParamJaMappings );
		LOG.debugf( "Binding filter definition: %s", filterDefinition.getFilterName() );
		context.getMetadataCollector().addFilterDefinition( filterDefinition );
	}

	@SuppressWarnings("unchecked")
	private static JdbcMapping resolveFilterParamType(Class<?> type, MetadataBuildingContext context) {
		if ( UserType.class.isAssignableFrom( type ) ) {
			return resolveUserType( (Class<UserType<?>>) type, context );
		}
		else if ( AttributeConverter.class.isAssignableFrom( type ) ) {
			return resolveAttributeConverter( (Class<AttributeConverter<?,?>>) type, context );
		}
		else if ( JavaType.class.isAssignableFrom( type ) ) {
			return resolveJavaType( (Class<JavaType<?>>) type, context );
		}
		else {
			return resolveBasicType( type, context );
		}
	}

	private static BasicType<Object> resolveBasicType(Class<?> type, MetadataBuildingContext context) {
		final TypeConfiguration typeConfiguration = context.getBootstrapContext().getTypeConfiguration();
		final JavaType<Object> jtd = typeConfiguration.getJavaTypeRegistry().findDescriptor( type );
		if ( jtd != null ) {
			final JdbcType jdbcType = jtd.getRecommendedJdbcType(
					new JdbcTypeIndicators() {
						@Override
						public TypeConfiguration getTypeConfiguration() {
							return typeConfiguration;
						}

						@Override
						public int getPreferredSqlTypeCodeForBoolean() {
							return context.getPreferredSqlTypeCodeForBoolean();
						}

						@Override
						public int getPreferredSqlTypeCodeForDuration() {
							return context.getPreferredSqlTypeCodeForDuration();
						}

						@Override
						public int getPreferredSqlTypeCodeForUuid() {
							return context.getPreferredSqlTypeCodeForUuid();
						}

						@Override
						public int getPreferredSqlTypeCodeForInstant() {
							return context.getPreferredSqlTypeCodeForInstant();
						}

						@Override
						public int getPreferredSqlTypeCodeForArray() {
							return context.getPreferredSqlTypeCodeForArray();
						}

						@Override
						public Dialect getDialect() {
							return context.getMetadataCollector().getDatabase().getDialect();
						}
					}
			);
			return typeConfiguration.getBasicTypeRegistry().resolve( jtd, jdbcType );
		}
		else {
			return null;
		}
	}

	private static JdbcMapping resolveUserType(Class<UserType<?>> type, MetadataBuildingContext context) {
		final StandardServiceRegistry serviceRegistry = context.getBootstrapContext().getServiceRegistry();
		final ManagedBeanRegistry beanRegistry = serviceRegistry.getService( ManagedBeanRegistry.class );
		final ManagedBean<UserType<?>> bean = beanRegistry.getBean( type );
		return new CustomType<>( bean.getBeanInstance(), context.getBootstrapContext().getTypeConfiguration() );
	}

	private static JdbcMapping resolveAttributeConverter(Class<AttributeConverter<?, ?>> type, MetadataBuildingContext context) {
		final StandardServiceRegistry serviceRegistry = context.getBootstrapContext().getServiceRegistry();
		final ManagedBeanRegistry beanRegistry = serviceRegistry.getService( ManagedBeanRegistry.class );
		final ManagedBean<AttributeConverter<?, ?>> bean = beanRegistry.getBean( type );

		final TypeConfiguration typeConfiguration = context.getBootstrapContext().getTypeConfiguration();
		final JavaTypeRegistry jtdRegistry = typeConfiguration.getJavaTypeRegistry();
		final JavaType<? extends AttributeConverter<?,?>> converterJtd = jtdRegistry.resolveDescriptor( bean.getBeanClass() );

		final ParameterizedType converterParameterizedType = GenericsHelper.extractParameterizedType( bean.getBeanClass() );
		final Class<?> domainJavaClass = GenericsHelper.extractClass( converterParameterizedType.getActualTypeArguments()[0] );
		final Class<?> relationalJavaClass = GenericsHelper.extractClass( converterParameterizedType.getActualTypeArguments()[1] );

		final JavaType<?> domainJtd = jtdRegistry.resolveDescriptor( domainJavaClass );
		final JavaType<?> relationalJtd = jtdRegistry.resolveDescriptor( relationalJavaClass );

		@SuppressWarnings({"rawtypes", "unchecked"})
		final JpaAttributeConverterImpl<?,?> valueConverter =
				new JpaAttributeConverterImpl( bean, converterJtd, domainJtd, relationalJtd );
		return new ConvertedBasicTypeImpl<>(
				ConverterDescriptor.TYPE_NAME_PREFIX
						+ valueConverter.getConverterJavaType().getJavaType().getTypeName(),
				String.format(
						"BasicType adapter for AttributeConverter<%s,%s>",
						domainJtd.getJavaType().getTypeName(),
						relationalJtd.getJavaType().getTypeName()
				),
				relationalJtd.getRecommendedJdbcType( typeConfiguration.getCurrentBaseSqlTypeIndicators() ),
				valueConverter
		);
	}

	private static JdbcMapping resolveJavaType(Class<JavaType<?>> type, MetadataBuildingContext context) {
		final TypeConfiguration typeConfiguration = context.getBootstrapContext().getTypeConfiguration();
		final JavaType<?> jtd = getJavaType( type, context, typeConfiguration );
		final JdbcType jdbcType = jtd.getRecommendedJdbcType( typeConfiguration.getCurrentBaseSqlTypeIndicators() );
		return typeConfiguration.getBasicTypeRegistry().resolve( jtd, jdbcType );
	}

	private static JavaType<?> getJavaType(
			Class<JavaType<?>> type,
			MetadataBuildingContext context,
			TypeConfiguration typeConfiguration) {
		final JavaType<?> registeredJtd = typeConfiguration.getJavaTypeRegistry().findDescriptor( type );
		if ( registeredJtd != null ) {
			return registeredJtd;
		}
		else {
			final StandardServiceRegistry serviceRegistry = context.getBootstrapContext().getServiceRegistry();
			final ManagedBeanRegistry beanRegistry = serviceRegistry.getService( ManagedBeanRegistry.class );
			return beanRegistry.getBean(type).getBeanInstance();
		}
	}

	public static void bindFetchProfilesForClass(XClass annotatedClass, MetadataBuildingContext context) {
		bindFetchProfiles( annotatedClass, context );
	}

	public static void bindFetchProfilesForPackage(ClassLoaderService cls, String packageName, MetadataBuildingContext context) {
		final Package pack = cls.packageForNameOrNull( packageName );
		if ( pack != null ) {
			final ReflectionManager reflectionManager = context.getBootstrapContext().getReflectionManager();
			bindFetchProfiles( reflectionManager.toXPackage( pack ), context );
		}
	}

	private static void bindFetchProfiles(XAnnotatedElement annotatedElement, MetadataBuildingContext context) {
		final FetchProfile fetchProfileAnnotation = annotatedElement.getAnnotation( FetchProfile.class );
		final FetchProfiles fetchProfileAnnotations = annotatedElement.getAnnotation( FetchProfiles.class );
		if ( fetchProfileAnnotation != null ) {
			bindFetchProfile( fetchProfileAnnotation, context );
		}
		if ( fetchProfileAnnotations != null ) {
			for ( FetchProfile profile : fetchProfileAnnotations.value() ) {
				bindFetchProfile( profile, context );
			}
		}
	}

	private static void bindFetchProfile(FetchProfile fetchProfileAnnotation, MetadataBuildingContext context) {
		for ( FetchProfile.FetchOverride fetch : fetchProfileAnnotation.fetchOverrides() ) {
			org.hibernate.annotations.FetchMode mode = fetch.mode();
			if ( !mode.equals( org.hibernate.annotations.FetchMode.JOIN ) ) {
				throw new MappingException( "Only FetchMode.JOIN is currently supported" );
			}
			context.getMetadataCollector().addSecondPass(
					new VerifyFetchProfileReferenceSecondPass( fetchProfileAnnotation.name(), fetch, context )
			);
		}
	}

	/**
	 * For the mapped entities build some temporary data-structure containing information about the
	 * inheritance status of a class.
	 *
	 * @param orderedClasses Order list of all annotated entities and their mapped superclasses
	 *
	 * @return A map of {@code InheritanceState}s keyed against their {@code XClass}.
	 */
	public static Map<XClass, InheritanceState> buildInheritanceStates(
			List<XClass> orderedClasses,
			MetadataBuildingContext buildingContext) {
		final Map<XClass, InheritanceState> inheritanceStatePerClass = new HashMap<>( orderedClasses.size() );
		for ( XClass clazz : orderedClasses ) {
			final InheritanceState superclassState = getSuperclassInheritanceState( clazz, inheritanceStatePerClass );
			final InheritanceState state = new InheritanceState( clazz, inheritanceStatePerClass, buildingContext );
			if ( superclassState != null ) {
				//the classes are ordered thus preventing an NPE
				superclassState.setHasSiblings( true );
				final InheritanceState superEntityState = getInheritanceStateOfSuperEntity( clazz, inheritanceStatePerClass );
				state.setHasParents( superEntityState != null );
				logMixedInheritance( clazz, superclassState, state );
				if ( superclassState.getType() != null ) {
					state.setType( superclassState.getType() );
				}
			}
			switch ( buildingContext.getMetadataCollector().getClassType( clazz ) ) {
				case ENTITY:
				case MAPPED_SUPERCLASS:
				case EMBEDDABLE:
					inheritanceStatePerClass.put( clazz, state );
			}
		}
		return inheritanceStatePerClass;
	}

	private static void logMixedInheritance(XClass clazz, InheritanceState superclassState, InheritanceState state) {
		if ( state.getType() != null && superclassState.getType() != null ) {
			final boolean nonDefault = InheritanceType.SINGLE_TABLE != state.getType();
			final boolean mixingStrategy = state.getType() != superclassState.getType();
			if ( nonDefault && mixingStrategy ) {
				//TODO: why on earth is this not an error!
				LOG.invalidSubStrategy( clazz.getName() );
			}
		}
	}
}
