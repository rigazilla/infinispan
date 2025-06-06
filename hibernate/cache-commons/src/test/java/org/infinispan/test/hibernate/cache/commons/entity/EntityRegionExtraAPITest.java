package org.infinispan.test.hibernate.cache.commons.entity;

import org.hibernate.cache.spi.access.AccessType;
import org.infinispan.test.hibernate.cache.commons.AbstractExtraAPITest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Tests for the "extra API" in EntityRegionAccessStrategy;.
 * <p>
 * By "extra API" we mean those methods that are superfluous to the
 * function of the JBC integration, where the impl is a no-op or a static
 * false return value, UnsupportedOperationException, etc.
 *
 * @author Galder Zamarreño
 * @since 3.5
 */
public class EntityRegionExtraAPITest extends AbstractExtraAPITest<Object> {
	public static final String VALUE1 = "VALUE1";
	public static final String VALUE2 = "VALUE2";

	@Override
	protected Object getAccessStrategy() {
		return TEST_SESSION_ACCESS.entityAccess(environment.getEntityRegion( REGION_NAME, accessType), accessType);
	}

	@Test
	@SuppressWarnings( {"UnnecessaryBoxing"})
	public void testAfterInsert() {
		boolean retval = testAccessStrategy.afterInsert(SESSION, KEY, VALUE1, Integer.valueOf( 1 ));
		assertEquals(accessType == AccessType.NONSTRICT_READ_WRITE, retval);
	}

	@Test
	@SuppressWarnings( {"UnnecessaryBoxing"})
	public void testAfterUpdate() {
		if (accessType == AccessType.READ_ONLY) {
			return;
		}
		boolean retval = testAccessStrategy.afterUpdate(SESSION,	KEY, VALUE2, Integer.valueOf( 1 ), Integer.valueOf( 2 ),	new MockSoftLock());
		assertEquals(accessType == AccessType.NONSTRICT_READ_WRITE, retval);
	}
}
