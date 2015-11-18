/*******************************************************************************
 * Copyright 2015, The IKANOW Open Source Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.ikanow.aleph2.search_service.elasticsearch.utils;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.hadoop.mapreduce.InputFormat;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;
import com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsAccessContext;
import com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadJobBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.BucketUtils;
import com.ikanow.aleph2.search_service.elasticsearch.hadoop.assets.Aleph2EsInputFormat;

import fj.data.Either;

public class TestElasticsearchHadoopUtils {

	@Test
	public void test_getAccessService() {

		new ElasticsearchHadoopUtils(); //code coverage!
		
		@SuppressWarnings("rawtypes")
		final IAnalyticsAccessContext<InputFormat> access_context =
				ElasticsearchHadoopUtils.getInputFormat(null); // (doesn't matter what the input is here)
		
		assertEquals(Either.right(Aleph2EsInputFormat.class), access_context.getAccessService());
	}
	
	@Test 
	public void test_getAccessConfig() {
		
		// No filter
		{
			final AnalyticThreadJobBean.AnalyticThreadJobInputBean job_input =
					BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
						.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::resource_name_or_id, "/test")					
					.done().get()
					;
			
			@SuppressWarnings("rawtypes")
			final IAnalyticsAccessContext<InputFormat> access_context =
					ElasticsearchHadoopUtils.getInputFormat(job_input); // (doesn't matter what the input is here)
			
			final Map<String, Object> res = access_context.getAccessConfig().get();
			assertEquals(Arrays.asList("es.index.read.missing.as.empty", "es.query", "es.resource"), res.keySet().stream().sorted().collect(Collectors.toList()));
			
			assertEquals("r__" + BucketUtils.getUniqueSignature("/test", Optional.empty()) + "*", res.get("es.resource"));
			assertEquals("?q=*", res.get("es.query"));
			assertEquals("yes", res.get("es.index.read.missing.as.empty"));
		}
		
		// More complex filter ("URL query")
		{
			final AnalyticThreadJobBean.AnalyticThreadJobInputBean job_input =
					BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
						.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::resource_name_or_id, "/test2")			
						.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::filter,
									new LinkedHashMap<String, Object>(ImmutableMap.<String, Object>builder()
										.put("technology_override", "?q=test")
									.build())								
								)
					.done().get()
					;
			
			@SuppressWarnings("rawtypes")
			final IAnalyticsAccessContext<InputFormat> access_context =
					ElasticsearchHadoopUtils.getInputFormat(job_input); // (doesn't matter what the input is here)
			
			final Map<String, Object> res = access_context.getAccessConfig().get();
			assertEquals(Arrays.asList("es.index.read.missing.as.empty", "es.query", "es.resource"), res.keySet().stream().sorted().collect(Collectors.toList()));
			
			assertEquals("r__" + BucketUtils.getUniqueSignature("/test2", Optional.empty()) + "*", res.get("es.resource"));
			assertEquals("?q=test", res.get("es.query"));
			assertEquals("yes", res.get("es.index.read.missing.as.empty"));			
		}
		
		// More complex filter (JSON)
		{
			final AnalyticThreadJobBean.AnalyticThreadJobInputBean job_input =
					BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
						.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::resource_name_or_id, "/test3")			
						.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::filter,
									new LinkedHashMap<String, Object>(ImmutableMap.<String, Object>builder()
										.put("technology_override", 
												ImmutableMap.<String, Object>builder()
													.put("test", "test2")
												.build()
												)
									.build())								
								)
					.done().get()
					;
			
			@SuppressWarnings("rawtypes")
			final IAnalyticsAccessContext<InputFormat> access_context =
					ElasticsearchHadoopUtils.getInputFormat(job_input); // (doesn't matter what the input is here)
			
			final Map<String, Object> res = access_context.getAccessConfig().get();
			assertEquals(Arrays.asList("es.index.read.missing.as.empty", "es.query", "es.resource"), res.keySet().stream().sorted().collect(Collectors.toList()));
			
			assertEquals("r__" + BucketUtils.getUniqueSignature("/test3", Optional.empty()) + "*", res.get("es.resource"));
			assertEquals("{\"test\":\"test2\"}", res.get("es.query"));
			assertEquals("yes", res.get("es.index.read.missing.as.empty"));						
		}
		
	}
}