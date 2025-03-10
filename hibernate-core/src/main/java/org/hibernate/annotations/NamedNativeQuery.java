/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.annotations;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.persistence.CacheRetrieveMode;
import jakarta.persistence.CacheStoreMode;
import org.hibernate.CacheMode;
import org.hibernate.Remove;

import static java.lang.annotation.ElementType.PACKAGE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Declares a named query written in native SQL.
 * <p>
 * Whereas {@link jakarta.persistence.NamedNativeQuery} allows settings to be
 * specified using stringly-typed {@link jakarta.persistence.QueryHint}s, this
 * annotation is typesafe.
 * <p>
 * Note that the members of this annotation correspond to hints enumerated by
 * {@link org.hibernate.jpa.AvailableHints}.
 *
 * @author Emmanuel Bernard
 *
 * @see org.hibernate.query.NativeQuery
 */
@Target({TYPE, PACKAGE})
@Retention(RUNTIME)
@Repeatable(NamedNativeQueries.class)
public @interface NamedNativeQuery {
	/**
	 * The name of this query.
	 *
	 * @see org.hibernate.SessionFactory#addNamedQuery
	 * @see org.hibernate.Session#createNamedQuery
	 */
	String name();

	/**
	 * The text of the SQL query.
	 */
	String query();

	/**
	 * The resulting {@code Class}.
	 * <p>
	 * Should not be used in conjunction with {@link #resultSetMapping()}
	 */
	Class<?> resultClass() default void.class;

	/**
	 * The name of a {@link jakarta.persistence.SqlResultSetMapping}.
	 * <p>
	 * Should not be used in conjunction with {@link #resultClass()}.
	 */
	String resultSetMapping() default "";

	/**
	 * The flush mode for the query.
	 *
	 * @see org.hibernate.query.CommonQueryContract#setFlushMode(jakarta.persistence.FlushModeType)
	 * @see org.hibernate.jpa.HibernateHints#HINT_FLUSH_MODE
	 */
	FlushModeType flushMode() default FlushModeType.PERSISTENCE_CONTEXT;

	/**
	 * Whether the query results are cacheable.
	 * Default is {@code false}, that is, not cacheable.
	 *
	 * @see org.hibernate.query.SelectionQuery#setCacheable(boolean)
	 * @see org.hibernate.jpa.HibernateHints#HINT_CACHEABLE
	 */
	boolean cacheable() default false;

	/**
	 * If the query results are cacheable, the name of the query cache region.
	 *
	 * @see org.hibernate.query.SelectionQuery#setCacheRegion(String)
	 * @see org.hibernate.jpa.HibernateHints#HINT_CACHE_REGION
	 */
	String cacheRegion() default "";

	/**
	 * The number of rows fetched by the JDBC driver per trip.
	 *
	 * @see org.hibernate.query.SelectionQuery#setFetchSize(int)
	 * @see org.hibernate.jpa.HibernateHints#HINT_FETCH_SIZE
	 */
	int fetchSize() default -1;

	/**
	 * The query timeout in seconds.
	 * Default is no timeout.
	 *
	 * @see org.hibernate.query.CommonQueryContract#setTimeout(int)
	 * @see org.hibernate.jpa.HibernateHints#HINT_TIMEOUT
	 * @see org.hibernate.jpa.SpecHints#HINT_SPEC_QUERY_TIMEOUT
	 */
	int timeout() default -1;

	/**
	 * A comment added to the SQL query.
	 * Useful when engaging with DBA.
	 *
	 * @see org.hibernate.query.CommonQueryContract#setComment(String)
	 * @see org.hibernate.jpa.HibernateHints#HINT_COMMENT
	 */
	String comment() default "";

	/**
	 * The cache storage mode for objects returned by this query.
	 *
	 * @see org.hibernate.query.Query#setCacheMode(CacheMode)
	 * @see org.hibernate.jpa.SpecHints#HINT_SPEC_CACHE_STORE_MODE
	 */
	CacheStoreMode cacheStoreMode() default CacheStoreMode.USE;

	/**
	 * The cache retrieval mode for objects returned by  this query.
	 *
	 * @see org.hibernate.query.Query#setCacheMode(CacheMode)
	 * @see org.hibernate.jpa.SpecHints#HINT_SPEC_CACHE_RETRIEVE_MODE
	 */
	CacheRetrieveMode cacheRetrieveMode() default CacheRetrieveMode.USE;

	/**
	 * The cache interaction mode for this query.
	 *
	 * @deprecated use {@link #cacheStoreMode()} and
	 *            {@link #cacheRetrieveMode()} since
	 *            {@link CacheModeType} is deprecated
	 *
	 * @see org.hibernate.jpa.HibernateHints#HINT_CACHE_MODE
	 */
	@Deprecated(since = "6.2") @Remove
	//TODO: actually, we won't remove it, we'll change its
	//      type to CacheMode and then un-deprecate it
	CacheModeType cacheMode() default CacheModeType.NORMAL;

	/**
	 * Whether the results should be loaded in read-only mode.
	 * Default is {@code false}.
	 *
	 * @see org.hibernate.query.SelectionQuery#setReadOnly(boolean)
	 * @see org.hibernate.jpa.HibernateHints#HINT_READ_ONLY
	 */
	boolean readOnly() default false;

	/**
	 * The query spaces involved in this query.
	 *
	 * @see org.hibernate.query.SynchronizeableQuery
	 * @see org.hibernate.jpa.HibernateHints#HINT_NATIVE_SPACES
	 */
	String[] querySpaces() default {};

	/**
	 * Is the {@linkplain #query() SQL query} a call to a stored procedure
	 * or function?
	 *
	 * @deprecated Calling database procedures and functions through
	 *             {@link org.hibernate.query.NativeQuery} is no longer supported;
	 *             use {@link jakarta.persistence.NamedStoredProcedureQuery} instead.
	 *
	 * @see org.hibernate.jpa.HibernateHints#HINT_CALLABLE_FUNCTION
	 */
	@Deprecated( since = "6.0" )
	boolean callable() default false;
}
