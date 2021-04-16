/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.boot.model.process.internal;

import java.util.function.Function;
import java.util.function.Supplier;
import javax.persistence.EnumType;
import javax.persistence.TemporalType;

import org.hibernate.MappingException;
import org.hibernate.mapping.BasicValue;
import org.hibernate.mapping.Selectable;
import org.hibernate.mapping.Table;
import org.hibernate.metamodel.mapping.JdbcMapping;
import org.hibernate.metamodel.model.convert.internal.NamedEnumValueConverter;
import org.hibernate.metamodel.model.convert.internal.OrdinalEnumValueConverter;
import org.hibernate.metamodel.model.domain.AllowableTemporalParameterType;
import org.hibernate.type.BasicType;
import org.hibernate.type.CustomType;
import org.hibernate.type.SerializableType;
import org.hibernate.type.SqlTypeDescriptorIndicatorCapable;
import org.hibernate.type.descriptor.java.BasicJavaDescriptor;
import org.hibernate.type.descriptor.java.EnumJavaTypeDescriptor;
import org.hibernate.type.descriptor.java.ImmutableMutabilityPlan;
import org.hibernate.type.descriptor.java.JavaTypeDescriptor;
import org.hibernate.type.descriptor.java.SerializableTypeDescriptor;
import org.hibernate.type.descriptor.java.TemporalJavaTypeDescriptor;
import org.hibernate.type.descriptor.jdbc.JdbcTypeDescriptor;
import org.hibernate.type.descriptor.jdbc.JdbcTypeDescriptorIndicators;
import org.hibernate.type.descriptor.jdbc.TinyIntTypeDescriptor;
import org.hibernate.type.spi.TypeConfiguration;

/**
 * BasicValue.Resolution resolver for cases where no explicit
 * type info was supplied.
 */
public class InferredBasicValueResolver {
	/**
	 * Create an inference-based resolution
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static BasicValue.Resolution from(
			Function<TypeConfiguration, BasicJavaDescriptor> explicitJavaTypeAccess,
			Function<TypeConfiguration, JdbcTypeDescriptor> explicitSqlTypeAccess,
			Supplier<JavaTypeDescriptor> reflectedJtdResolver,
			JdbcTypeDescriptorIndicators stdIndicators,
			Table table,
			Selectable selectable,
			String ownerName,
			String propertyName,
			TypeConfiguration typeConfiguration) {

		final BasicJavaDescriptor explicitJavaType = explicitJavaTypeAccess != null ? explicitJavaTypeAccess.apply( typeConfiguration ) : null;
		final JdbcTypeDescriptor explicitJdbcType = explicitSqlTypeAccess != null ? explicitSqlTypeAccess.apply( typeConfiguration ) : null;

		final BasicJavaDescriptor reflectedJtd = (BasicJavaDescriptor) reflectedJtdResolver.get();

		// NOTE : the distinction that is made below wrt `explicitJavaType` and `reflectedJtd` is
		//		needed temporarily to trigger "legacy resolution" versus "ORM6 resolution.  Yes, it
		//		makes the code a little more complicated but the benefit is well worth it - saving memory

		final BasicType<?> jdbcMapping;
		final BasicType<?> legacyType;

		if ( explicitJavaType != null ) {
			// we have an explicit @JavaType

			if ( explicitJdbcType != null ) {
				// we also have an explicit @SqlType(Code)

				jdbcMapping = typeConfiguration.getBasicTypeRegistry().resolve(
						explicitJavaType,
						explicitJdbcType
				);
			}
			else {
				// infer the STD
				final JdbcTypeDescriptor inferredJdbcType = explicitJavaType.getRecommendedJdbcType( stdIndicators );
				jdbcMapping = typeConfiguration.getBasicTypeRegistry().resolve(
						explicitJavaType,
						inferredJdbcType
				);
			}

			legacyType = jdbcMapping;
		}
		else if ( reflectedJtd != null ) {
			// we were able to determine the "reflected java-type"

			if ( explicitJdbcType != null ) {
				// we also have an explicit @SqlType(Code)

				jdbcMapping = typeConfiguration.getBasicTypeRegistry().resolve(
						reflectedJtd,
						explicitJdbcType
				);

				legacyType = jdbcMapping;
			}
			else {
				// Use JTD if we know it to apply any specialized resolutions

				if ( reflectedJtd instanceof EnumJavaTypeDescriptor ) {
					return fromEnum(
							(EnumJavaTypeDescriptor) reflectedJtd,
							explicitJavaTypeAccess.apply( typeConfiguration ),
							explicitSqlTypeAccess.apply( typeConfiguration ),
							stdIndicators,
							typeConfiguration
					);
				}
				else if ( reflectedJtd instanceof TemporalJavaTypeDescriptor ) {
					return fromTemporal(
							(TemporalJavaTypeDescriptor) reflectedJtd,
							explicitJavaTypeAccess,
							explicitSqlTypeAccess,
							stdIndicators,
							typeConfiguration
					);
				}
				else {
					// here we have the legacy case
					//		- we mimic how this used to be done
					final BasicType registeredType = typeConfiguration.getBasicTypeRegistry().getRegisteredType( reflectedJtd.getJavaType() );

					if ( registeredType != null ) {
						// reuse the "legacy type"
						legacyType = resolveSqlTypeIndicators( stdIndicators, registeredType );
						jdbcMapping = legacyType;
					}
					else if ( reflectedJtd instanceof SerializableTypeDescriptor ) {
						legacyType = new SerializableType<>( reflectedJtd.getJavaTypeClass() );
						jdbcMapping = legacyType;
					}
					else {
						// let this fall through to the exception creation below
						legacyType = null;
						jdbcMapping = null;
					}
				}
			}
		}
		else {
			if ( explicitJdbcType != null ) {
				// we have an explicit STD, but no JTD - infer JTD
				//		- NOTE : yes its an odd case, but its easy to implement here, so...
				final BasicJavaDescriptor recommendedJtd = explicitJdbcType.getJdbcRecommendedJavaTypeMapping( typeConfiguration );
				final BasicType<?> resolved = typeConfiguration.getBasicTypeRegistry().resolve(
						recommendedJtd,
						explicitJdbcType
				);

				jdbcMapping = resolveSqlTypeIndicators( stdIndicators, resolved );
				legacyType = jdbcMapping;
			}
			else {
				// we have neither a JTD nor STD

				throw new MappingException(
						"Could not determine JavaTypeDescriptor nor SqlTypeDescriptor to use" +
								" for BasicValue: owner = " + ownerName +
								"; property = " + propertyName +
								"; table = " + table.getName() +
								"; column = " + selectable.getText()
				);
			}
		}

		if ( jdbcMapping == null ) {
			throw new MappingException(
					"Could not determine JavaTypeDescriptor nor SqlTypeDescriptor to use" + "" +
							" for " + ( (BasicValue) stdIndicators ).getResolvedJavaType() +
							"; table = " + table.getName() +
							"; column = " + selectable.getText()
			);
		}

		return new InferredBasicValueResolution(
				jdbcMapping,
				jdbcMapping.getJavaTypeDescriptor(),
				jdbcMapping.getJavaTypeDescriptor(),
				jdbcMapping.getJdbcTypeDescriptor(),
				null,
				legacyType,
				null
		);
	}

	@SuppressWarnings("rawtypes")
	public static BasicType<?> resolveSqlTypeIndicators(
			JdbcTypeDescriptorIndicators stdIndicators,
			BasicType<?> resolved) {
		if ( resolved instanceof SqlTypeDescriptorIndicatorCapable ) {
			final SqlTypeDescriptorIndicatorCapable indicatorCapable = (SqlTypeDescriptorIndicatorCapable) resolved;
			final BasicType indicatedType = indicatorCapable.resolveIndicatedType( stdIndicators );
			return indicatedType != null ? indicatedType : resolved;
		}
		else {
			return resolved;
		}
	}

	@SuppressWarnings("rawtypes")
	public static InferredBasicValueResolution fromEnum(
			EnumJavaTypeDescriptor enumJavaDescriptor,
			BasicJavaDescriptor explicitJavaType,
			JdbcTypeDescriptor explicitJdbcType,
			JdbcTypeDescriptorIndicators stdIndicators,
			TypeConfiguration typeConfiguration) {
		final EnumType enumStyle = stdIndicators.getEnumeratedType() != null
				? stdIndicators.getEnumeratedType()
				: EnumType.ORDINAL;

		switch ( enumStyle ) {
			case STRING: {
				final JavaTypeDescriptor<?> relationalJtd;
				if ( explicitJavaType != null ) {
					if ( ! String.class.isAssignableFrom( explicitJavaType.getJavaTypeClass() ) ) {
						throw new MappingException(
								"Explicit JavaTypeDescriptor [" + explicitJavaType +
										"] applied to enumerated value with EnumType#STRING" +
										" should handle `java.lang.String` as its relational type descriptor"
						);
					}
					relationalJtd = explicitJavaType;
				}
				else {
					final boolean useCharacter = stdIndicators.getColumnLength() == 1;
					final Class<?> relationalJavaType = useCharacter ? Character.class : String.class;
					relationalJtd = typeConfiguration.getJavaTypeDescriptorRegistry().getDescriptor( relationalJavaType );
				}

				final JdbcTypeDescriptor jdbcTypeDescriptor = explicitJdbcType != null ? explicitJdbcType : relationalJtd.getRecommendedJdbcType( stdIndicators );

				//noinspection unchecked
				final NamedEnumValueConverter valueConverter = new NamedEnumValueConverter(
						enumJavaDescriptor,
						jdbcTypeDescriptor,
						relationalJtd
				);

				//noinspection unchecked
				final org.hibernate.type.EnumType legacyEnumType = new org.hibernate.type.EnumType(
						enumJavaDescriptor.getJavaTypeClass(),
						valueConverter,
						typeConfiguration
				);

				final CustomType legacyEnumTypeWrapper = new CustomType( legacyEnumType, typeConfiguration );

				final JdbcMapping jdbcMapping = typeConfiguration.getBasicTypeRegistry().resolve( relationalJtd, jdbcTypeDescriptor );

				//noinspection unchecked
				return new InferredBasicValueResolution(
						jdbcMapping,
						enumJavaDescriptor,
						relationalJtd,
						jdbcTypeDescriptor,
						valueConverter,
						legacyEnumTypeWrapper,
						ImmutableMutabilityPlan.INSTANCE
				);
			}
			case ORDINAL: {
				final JavaTypeDescriptor<Integer> relationalJtd;
				if ( explicitJavaType != null ) {
					if ( ! Integer.class.isAssignableFrom( explicitJavaType.getJavaTypeClass() ) ) {
						throw new MappingException(
								"Explicit JavaTypeDescriptor [" + explicitJavaType +
										"] applied to enumerated value with EnumType#ORDINAL" +
										" should handle `java.lang.Integer` as its relational type descriptor"
						);
					}
					//noinspection unchecked
					relationalJtd = explicitJavaType;
				}
				else {
					relationalJtd = typeConfiguration.getJavaTypeDescriptorRegistry().getDescriptor( Integer.class );
				}

				final JdbcTypeDescriptor jdbcTypeDescriptor = explicitJdbcType != null ? explicitJdbcType : TinyIntTypeDescriptor.INSTANCE;

				//noinspection unchecked
				final OrdinalEnumValueConverter valueConverter = new OrdinalEnumValueConverter(
						enumJavaDescriptor,
						jdbcTypeDescriptor,
						relationalJtd
				);

				//noinspection unchecked
				final org.hibernate.type.EnumType legacyEnumType = new org.hibernate.type.EnumType(
						enumJavaDescriptor.getJavaTypeClass(),
						valueConverter,
						typeConfiguration
				);

				final CustomType legacyEnumTypeWrapper = new CustomType( legacyEnumType, typeConfiguration );

				final JdbcMapping jdbcMapping = typeConfiguration.getBasicTypeRegistry().resolve( relationalJtd, jdbcTypeDescriptor );

				//noinspection unchecked
				return new InferredBasicValueResolution(
						jdbcMapping,
						enumJavaDescriptor,
						relationalJtd,
						jdbcTypeDescriptor,
						valueConverter,
						legacyEnumTypeWrapper,
						ImmutableMutabilityPlan.INSTANCE
				);
			}
			default: {
				throw new MappingException( "Unknown enumeration-style (JPA EnumType) : " + enumStyle );
			}
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	public static InferredBasicValueResolution fromTemporal(
			TemporalJavaTypeDescriptor reflectedJtd,
			Function<TypeConfiguration, BasicJavaDescriptor> explicitJavaTypeAccess,
			Function<TypeConfiguration, JdbcTypeDescriptor> explicitSqlTypeAccess,
			JdbcTypeDescriptorIndicators stdIndicators,
			TypeConfiguration typeConfiguration) {
		final TemporalType requestedTemporalPrecision = stdIndicators.getTemporalPrecision();

		final JavaTypeDescriptor explicitJavaType;
		if ( explicitJavaTypeAccess != null ) {
			explicitJavaType = explicitJavaTypeAccess.apply( typeConfiguration );
		}
		else {
			explicitJavaType = null;
		}

		final JdbcTypeDescriptor explicitJdbcType;
		if ( explicitSqlTypeAccess != null ) {
			explicitJdbcType = explicitSqlTypeAccess.apply( typeConfiguration );
		}
		else {
			explicitJdbcType = null;
		}

		// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
		// Case #1 - @JavaType

		if ( explicitJavaType != null ) {
			if ( !TemporalJavaTypeDescriptor.class.isInstance( explicitJavaType ) ) {
				throw new MappingException(
						"Explicit JavaTypeDescriptor [" + explicitJavaType +
								"] defined for temporal value must implement TemporalJavaTypeDescriptor"
				);
			}

			final TemporalJavaTypeDescriptor explicitTemporalJtd = (TemporalJavaTypeDescriptor) explicitJavaType;

			if ( requestedTemporalPrecision != null && explicitTemporalJtd.getPrecision() != requestedTemporalPrecision ) {
				throw new MappingException(
						"Temporal precision (`javax.persistence.TemporalType`) mismatch... requested precision = " + requestedTemporalPrecision +
								"; explicit JavaTypeDescriptor (`" + explicitTemporalJtd + "`) precision = " + explicitTemporalJtd.getPrecision()

				);
			}

			final JdbcTypeDescriptor jdbcTypeDescriptor = explicitJdbcType != null ? explicitJdbcType : explicitTemporalJtd.getRecommendedJdbcType( stdIndicators );

			final BasicType jdbcMapping = typeConfiguration.getBasicTypeRegistry().resolve( explicitTemporalJtd, jdbcTypeDescriptor );

			return new InferredBasicValueResolution(
					jdbcMapping,
					explicitTemporalJtd,
					explicitTemporalJtd,
					jdbcTypeDescriptor,
					null,
					jdbcMapping,
					explicitJavaType.getMutabilityPlan()
			);
		}

		// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
		// Case #2 - SqlType(Code)
		//
		// 		- still a special case because we want to perform the new resolution
		// 		due to the new annotations being used

		if ( explicitJdbcType != null ) {
			final TemporalJavaTypeDescriptor jtd;

			if ( requestedTemporalPrecision != null ) {
				jtd = reflectedJtd.resolveTypeForPrecision(
						requestedTemporalPrecision,
						typeConfiguration
				);
			}
			else {
				jtd = reflectedJtd;
			}

			final BasicType jdbcMapping = typeConfiguration.getBasicTypeRegistry().resolve( jtd, explicitJdbcType );

			return new InferredBasicValueResolution(
					jdbcMapping,
					jtd,
					jtd,
					explicitJdbcType,
					null,
					jdbcMapping,
					jtd.getMutabilityPlan()
			);
		}

		// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
		// Case #3 - no @JavaType nor @SqlType(Code)
		//
		// 		- for the moment continue to use the legacy resolution to registered
		// 		BasicType

		final BasicType registeredType = typeConfiguration.getBasicTypeRegistry().getRegisteredType( reflectedJtd.getJavaType() );
		final AllowableTemporalParameterType legacyTemporalType = (AllowableTemporalParameterType) registeredType;

		final BasicType basicType;
		if ( requestedTemporalPrecision != null ) {
			basicType = (BasicType) legacyTemporalType.resolveTemporalPrecision(
					requestedTemporalPrecision,
					typeConfiguration
			);
		}
		else {
			basicType = registeredType;
		}

		return new InferredBasicValueResolution(
				basicType,
				basicType.getJavaTypeDescriptor(),
				basicType.getJavaTypeDescriptor(),
				basicType.getJdbcTypeDescriptor(),
				null,
				basicType,
				reflectedJtd.getMutabilityPlan()
		);
	}

}