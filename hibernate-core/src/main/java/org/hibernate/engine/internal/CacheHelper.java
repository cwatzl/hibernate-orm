/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.engine.internal;

import org.hibernate.cache.spi.access.CachedDomainDataAccess;
import org.hibernate.engine.spi.SessionEventListenerManager;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

/**
 * @author Steve Ebersole
 * @author Sanne Grinovero
 */
public final class CacheHelper {

	private CacheHelper() {
	}

	public static Object fromSharedCache(
			SharedSessionContractImplementor session,
			Object cacheKey,
			CachedDomainDataAccess cacheAccess) {
		final SessionEventListenerManager eventListenerManager = session.getEventListenerManager();
		Object cachedValue = null;
		eventListenerManager.cacheGetStart();
		try {
			cachedValue = cacheAccess.get( session, cacheKey );
		}
		finally {
			eventListenerManager.cacheGetEnd( cachedValue != null );
		}
		return cachedValue;
	}

}
