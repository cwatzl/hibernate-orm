/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.metamodel.internal;

import java.lang.reflect.Constructor;

import org.hibernate.InstantiationException;
import org.hibernate.PropertyNotFoundException;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.internal.CoreLogging;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.internal.util.ReflectHelper;
import org.hibernate.type.descriptor.java.JavaTypeDescriptor;

/**
 * @author Steve Ebersole
 */
public class PojoInstantiatorImpl<J> extends AbstractPojoInstantiator {
	private static final CoreMessageLogger LOG = CoreLogging.messageLogger( PojoInstantiatorImpl.class );

	private final Constructor constructor;

	@SuppressWarnings("WeakerAccess")
	public PojoInstantiatorImpl(JavaTypeDescriptor javaTypeDescriptor) {
		super( javaTypeDescriptor.getJavaTypeClass() );

		this.constructor = isAbstract()
				? null
				: resolveConstructor( getMappedPojoClass() );
	}

	protected static Constructor resolveConstructor(Class mappedPojoClass) {
		try {
			//noinspection unchecked
			return ReflectHelper.getDefaultConstructor( mappedPojoClass);
		}
		catch ( PropertyNotFoundException e ) {
			LOG.noDefaultConstructor( mappedPojoClass.getName() );
		}

		return null;
	}


	@Override
	@SuppressWarnings("unchecked")
	public J instantiate(SessionFactoryImplementor sessionFactory) {
		if ( isAbstract() ) {
			throw new InstantiationException( "Cannot instantiate abstract class or interface: ", getMappedPojoClass() );
		}
		else if ( constructor == null ) {
			throw new InstantiationException( "No default constructor for entity: ", getMappedPojoClass() );
		}
		else {
			try {
				return (J) applyInterception( constructor.newInstance( (Object[]) null ) );
			}
			catch ( Exception e ) {
				throw new InstantiationException( "Could not instantiate entity: ", getMappedPojoClass(), e );
			}
		}
	}

	protected Object applyInterception(Object entity) {
		return entity;
	}

}