/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cyclop.validation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import javax.inject.Inject;

import org.cyclop.model.CqlKeySpace;
import org.cyclop.model.CqlTable;
import org.cyclop.model.exception.BeanValidationException;
import org.cyclop.model.exception.ServiceException;
import org.cyclop.test.AbstractTestCase;
import org.junit.Test;

public class TestAopValidator extends AbstractTestCase {

	@Inject
	private DummyValService service;

	@Test
	public void testSingleStringParamOK() {
		assertEquals("abc", service.singleStringParam("abc"));
	}

	@Test
	public void testSingleNullParamViolation() {
		try {
			assertEquals("abc", service.singleStringParam(null));
			fail();
		} catch (BeanValidationException ve) {
			String msg = ve.getMessage();
			assertTrue(msg, msg.contains("METHOD_PARAM_INDEX_0=[NULL_ROOT_OBJECT]"));
		}
	}

	@Test
	public void testMultpileParamsAllSetOk() {
		assertEquals(new CqlKeySpace("space"),
				service.mixedParams(new CqlKeySpace("space"), new CqlTable("table"), "123"));
	}

	@Test
	public void testMultpileParamsWithViolations() {
		try {
			service.mixedParams(new CqlKeySpace(" "), new CqlTable(" "), null);
			fail();
		} catch (BeanValidationException ve) {
			String msg = ve.getMessage();
			assertTrue(msg, msg.contains("METHOD_PARAM_INDEX_0=[ConstraintViolation"));
			assertTrue(msg, msg.contains("METHOD_PARAM_INDEX_1=[ConstraintViolation"));
			assertTrue(msg, msg.contains("METHOD_PARAM_INDEX_2=[NULL_ROOT_OBJECT]"));
		}
	}

	@Test
	public void testMultpileParamsMinSetOk() {
		assertEquals(new CqlKeySpace("space"), service.mixedParams(new CqlKeySpace("space"), null, "123"));
	}

	@Test
	public void testReturnNullObjectViolation() {

		try {
			service.mixedParams(null, new CqlTable("table"), "123");
			fail();
		} catch (BeanValidationException ve) {
			String msg = ve.getMessage();
			assertTrue(msg, msg.contains("METHOD_RETURN_VALUE=[NULL_ROOT_OBJECT]"));
		}
	}

	@Test
	public void testFirstParamViolation() {

		try {
			service.mixedParams(null, new CqlTable(" "), "123");
			fail();
		} catch (BeanValidationException ve) {
			String msg = ve.getMessage();
			assertTrue(msg, msg.contains("METHOD_PARAM_INDEX_1="));

			assertTrue(msg, msg.contains("propertyPath=partLc"));
		}
	}

	@Test(expected = ServiceException.class)
	public void testThrowsException() throws Exception {
		service.singleParamThrows(new CqlTable("throw"));
	}

	@Test
	public void testReturnNotEmptyViolation() {
		try {
			service.returnOptional(new CqlKeySpace(" "));
			fail();
		} catch (BeanValidationException ve) {
			String msg = ve.getMessage();
			assertTrue(msg, msg.contains("METHOD_RETURN_VALUE"));
			assertTrue(msg, msg.contains("propertyPath=partLc, rootBeanClass=class org.cyclop.model.CqlKeySpace"));
		}
	}

	@Test
	public void testNoValidation() throws Exception {
		assertNull(null, service.novalidation(null));
		assertEquals(new CqlKeySpace(" "), service.novalidation(new CqlKeySpace(" ")));
	}

}
