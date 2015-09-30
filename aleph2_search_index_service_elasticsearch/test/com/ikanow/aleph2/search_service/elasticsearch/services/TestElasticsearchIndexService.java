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
 ******************************************************************************/
package com.ikanow.aleph2.search_service.elasticsearch.services;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesRequest;
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesResponse;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.collect.ImmutableSet;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IDataWriteService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService.IBatchSubservice;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ISecurityService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.MockSecurityService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.MockServiceContext;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean.TemporalSchemaBean;
import com.ikanow.aleph2.data_model.objects.shared.BasicMessageBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.ErrorUtils;
import com.ikanow.aleph2.data_model.utils.Lambdas;
import com.ikanow.aleph2.data_model.utils.TimeUtils;
import com.ikanow.aleph2.search_service.elasticsearch.data_model.ElasticsearchIndexServiceConfigBean;
import com.ikanow.aleph2.search_service.elasticsearch.data_model.ElasticsearchIndexServiceConfigBean.SearchIndexSchemaDefaultBean;
import com.ikanow.aleph2.search_service.elasticsearch.utils.ElasticsearchIndexConfigUtils;
import com.ikanow.aleph2.search_service.elasticsearch.utils.ElasticsearchIndexUtils;
import com.ikanow.aleph2.shared.crud.elasticsearch.data_model.ElasticsearchConfigurationBean;
import com.ikanow.aleph2.shared.crud.elasticsearch.data_model.ElasticsearchContext;
import com.ikanow.aleph2.shared.crud.elasticsearch.services.ElasticsearchCrudServiceFactory;
import com.ikanow.aleph2.shared.crud.elasticsearch.services.IElasticsearchCrudServiceFactory;
import com.ikanow.aleph2.shared.crud.elasticsearch.services.MockElasticsearchCrudServiceFactory;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public class TestElasticsearchIndexService {

	public static ObjectMapper _mapper = BeanTemplateUtils.configureMapper(Optional.empty());	
	
	protected MockServiceContext _service_context;
	protected MockElasticsearchIndexService _index_service;
	protected IElasticsearchCrudServiceFactory _crud_factory;
	protected ElasticsearchIndexServiceConfigBean _config_bean;
	protected MockSecurityService _security_service;
	
	// Set this string to connect vs a real DB
	private final String _connection_string = null;
	private final String _cluster_name = null;
//	private final String _connection_string = "localhost:4093";
//	private final String _cluster_name = "infinite-dev";
	
	@Before
	public void setupServices() {
		final Config full_config = ConfigFactory.empty();		
		if (null == _connection_string) {
			_crud_factory = new MockElasticsearchCrudServiceFactory();
		}
		else {
			final ElasticsearchConfigurationBean config_bean = new ElasticsearchConfigurationBean(_connection_string, _cluster_name);
			_crud_factory = new ElasticsearchCrudServiceFactory(config_bean);
		}
		_config_bean = ElasticsearchIndexConfigUtils.buildConfigBean(full_config);
		
		_security_service = new MockSecurityService();
		
		_service_context = new MockServiceContext();
		_service_context.addService(ISecurityService.class, Optional.empty(), _security_service);
		
		_index_service = new MockElasticsearchIndexService(_service_context, _crud_factory, _config_bean);
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	// VALIDATION
	
	@Test
	public void test_verbosenessSettings() {
		final List<Object> l_true = Arrays.asList(1, "1", "true", true, "TRUE", "True");
		final List<Object> l_false = Arrays.asList(0, "0", "false", false, "FALSE", "False");
		
		for (Object o: l_true) {
			final DataSchemaBean.SearchIndexSchemaBean s = BeanTemplateUtils.build(DataSchemaBean.SearchIndexSchemaBean.class)
																.with(DataSchemaBean.SearchIndexSchemaBean::technology_override_schema, 
																		ImmutableMap.builder().put("verbose", o).build()
																).done().get();
			assertEquals(true, ElasticsearchIndexService.is_verbose(s));
		}
		for (Object o: l_false) {
			final DataSchemaBean.SearchIndexSchemaBean s = BeanTemplateUtils.build(DataSchemaBean.SearchIndexSchemaBean.class)
																.with(DataSchemaBean.SearchIndexSchemaBean::technology_override_schema, 
																		ImmutableMap.builder().put("verbose", o).build()
																).done().get();
			assertEquals(false, ElasticsearchIndexService.is_verbose(s));
		}

		// (not present)
		{
			final DataSchemaBean.SearchIndexSchemaBean s = BeanTemplateUtils.build(DataSchemaBean.SearchIndexSchemaBean.class)
					.with(DataSchemaBean.SearchIndexSchemaBean::technology_override_schema, 
							ImmutableMap.builder().build()
					).done().get();
			assertEquals(false, ElasticsearchIndexService.is_verbose(s));
		}
		{
			final DataSchemaBean.SearchIndexSchemaBean s = BeanTemplateUtils.build(DataSchemaBean.SearchIndexSchemaBean.class)
						.done().get();
			assertEquals(false, ElasticsearchIndexService.is_verbose(s));
		}
	}
	
	@Test
	public void test_validationSuccess() throws IOException {
		final String bucket_str = Resources.toString(Resources.getResource("com/ikanow/aleph2/search_service/elasticsearch/services/test_bucket_validate_success.json"), Charsets.UTF_8);
		final DataBucketBean bucket = BeanTemplateUtils.build(bucket_str, DataBucketBean.class).done().get();
		
		// 1) Verbose mode off
		{			
			final Collection<BasicMessageBean> res_col = _index_service.validateSchema(bucket.data_schema().columnar_schema(), bucket)._2();
			final Collection<BasicMessageBean> res_search = _index_service.validateSchema(bucket.data_schema().search_index_schema(), bucket)._2();
			final Collection<BasicMessageBean> res_time = _index_service.validateSchema(bucket.data_schema().temporal_schema(), bucket)._2();
			
			assertEquals(0, res_col.size());
			assertEquals(0, res_search.size());
			assertEquals(0, res_time.size());
		}
		
		// 2) Verbose mode on
		{
			final DataBucketBean bucket_verbose = BeanTemplateUtils.clone(bucket)
													.with(DataBucketBean::data_schema, 
															BeanTemplateUtils.clone(bucket.data_schema())
																.with(
																	DataSchemaBean::search_index_schema,
																	BeanTemplateUtils.clone(bucket.data_schema().search_index_schema())
																		.with(DataSchemaBean.SearchIndexSchemaBean::technology_override_schema,
																				ImmutableMap.builder()
																					.putAll(bucket.data_schema().search_index_schema().technology_override_schema())
																					.put("verbose", true)
																					.build()
																		)
																		.done()
																)
																.done()
													)
													.done();
			
			final Collection<BasicMessageBean> res_col = _index_service.validateSchema(bucket_verbose.data_schema().columnar_schema(), bucket)._2();
			final Collection<BasicMessageBean> res_search = _index_service.validateSchema(bucket_verbose.data_schema().search_index_schema(), bucket)._2();
			final Collection<BasicMessageBean> res_time = _index_service.validateSchema(bucket_verbose.data_schema().temporal_schema(), bucket)._2();
			
			assertEquals(0, res_col.size());
			assertEquals(0, res_time.size());
			assertEquals(2, res_search.size());
			assertEquals(true, res_search.stream().allMatch(BasicMessageBean::success));
			Iterator<BasicMessageBean> res_search_message = res_search.iterator();
			
			final String mapping_str = Resources.toString(Resources.getResource("com/ikanow/aleph2/search_service/elasticsearch/services/test_verbose_mapping_validate_results.json"), Charsets.UTF_8);
			final JsonNode mapping_json = _mapper.readTree(mapping_str.getBytes());
			assertEquals(mapping_json.toString(), _mapper.readTree(res_search_message.next().message()).toString());
			assertTrue("Sets the max index override: " + res_search.stream().skip(1).map(m->m.message()).collect(Collectors.joining()), res_search_message.next().message().contains("1,000 MB"));
		}
		
		// 3) Temporal
		
		{
			final DataBucketBean bucket_temporal_no_grouping = BeanTemplateUtils.clone(bucket)
					.with(DataBucketBean::data_schema, 
							BeanTemplateUtils.clone(bucket.data_schema())
								.with(DataSchemaBean::temporal_schema,
										BeanTemplateUtils.build(DataSchemaBean.TemporalSchemaBean.class)
										.done().get()
								).done()
						).done();
			
			assertEquals("", _index_service.validateSchema(bucket_temporal_no_grouping.data_schema().temporal_schema(), bucket)._1());
			
			final DataBucketBean bucket_temporal_grouping = BeanTemplateUtils.clone(bucket)
					.with(DataBucketBean::data_schema, 
							BeanTemplateUtils.clone(bucket.data_schema())
								.with(DataSchemaBean::temporal_schema,
										BeanTemplateUtils.build(DataSchemaBean.TemporalSchemaBean.class)
											.with(DataSchemaBean.TemporalSchemaBean::grouping_time_period, "1d")
										.done().get()
								).done()
						).done();
			
			assertEquals("_{yyyy-MM-dd}", _index_service.validateSchema(bucket_temporal_grouping.data_schema().temporal_schema(), bucket)._1());
		}
	}
	
	@Test
	public void test_validationFail() throws IOException {
		
		final String bucket_str = Resources.toString(Resources.getResource("com/ikanow/aleph2/search_service/elasticsearch/services/test_bucket_validate_fail.json"), Charsets.UTF_8);
		final DataBucketBean bucket = BeanTemplateUtils.build(bucket_str, DataBucketBean.class).done().get();
		
		// 1) Verbose mode off
		{			
			final Collection<BasicMessageBean> res_col = _index_service.validateSchema(bucket.data_schema().columnar_schema(), bucket)._2();
			final Collection<BasicMessageBean> res_search = _index_service.validateSchema(bucket.data_schema().search_index_schema(), bucket)._2();
			final Collection<BasicMessageBean> res_time = _index_service.validateSchema(bucket.data_schema().temporal_schema(), bucket)._2();
			
			assertEquals(0, res_col.size());
			assertEquals(0, res_time.size());
			assertEquals(1, res_search.size());
			
			final BasicMessageBean res_search_message = res_search.iterator().next();
			assertEquals(false, res_search_message.success());
		}
		
		// 2) Check setting an invalid max index size
		{
			final String bucket_str_2 = Resources.toString(Resources.getResource("com/ikanow/aleph2/search_service/elasticsearch/services/test_bucket_validate_success.json"), Charsets.UTF_8);
			final DataBucketBean bucket2 = BeanTemplateUtils.build(bucket_str_2, DataBucketBean.class).done().get();
			final DataBucketBean bucket_too_small = BeanTemplateUtils.clone(bucket2)
					.with(DataBucketBean::data_schema, 
							BeanTemplateUtils.clone(bucket2.data_schema())
								.with(
									DataSchemaBean::search_index_schema,
									BeanTemplateUtils.clone(bucket2.data_schema().search_index_schema())
										.with(DataSchemaBean.SearchIndexSchemaBean::target_index_size_mb, 10L)
										.done()
								)
								.done()
					)
					.done();
			final Collection<BasicMessageBean> res_search = _index_service.validateSchema(bucket.data_schema().search_index_schema(), bucket_too_small)._2();
			assertEquals(1, res_search.size());
			assertEquals(false, res_search.stream().allMatch(BasicMessageBean::success));
			BasicMessageBean res_search_message = res_search.iterator().next();
			assertTrue("Right message: " + res_search_message.message(), res_search_message.message().contains("10 MB"));			
		}
		
		// 3) Check overriding the search index fails when not an admin
		{
			final String bucket_str_2 = Resources.toString(Resources.getResource("com/ikanow/aleph2/search_service/elasticsearch/services/test_bucket_validate_success.json"), Charsets.UTF_8);
			final DataBucketBean bucket2 = BeanTemplateUtils.build(bucket_str_2, DataBucketBean.class).done().get();

			final DataSchemaBean.SearchIndexSchemaBean s = BeanTemplateUtils.build(DataSchemaBean.SearchIndexSchemaBean.class)
					.with(DataSchemaBean.SearchIndexSchemaBean::technology_override_schema, 
							ImmutableMap.builder().put(SearchIndexSchemaDefaultBean.index_name_override_, "test").build()
					).done().get();

			
			final DataBucketBean bucket_with_override = BeanTemplateUtils.clone(bucket2)
					.with(DataBucketBean::data_schema, 
							BeanTemplateUtils.clone(bucket2.data_schema())
								.with(
									DataSchemaBean::search_index_schema, s
								)
								.done()
					)
					.done();
			
			// Works for admins
			_security_service.setGlobalMockRole("admin", true);

			final Collection<BasicMessageBean> res_search_yes = _index_service.validateSchema(bucket.data_schema().search_index_schema(), bucket_with_override)._2();
			assertEquals(0, res_search_yes.size());			
			
			// Fails for non admins
			_security_service.setGlobalMockRole("admin", false);
			
			final Collection<BasicMessageBean> res_search_no = _index_service.validateSchema(bucket.data_schema().search_index_schema(), bucket_with_override)._2();
			assertEquals(1, res_search_no.size());						
		}
		
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	// INDEX NOT ENABLED
	
	@Test
	public void test_indexNotEnabled() {
		
		final DataBucketBean db1 = BeanTemplateUtils.build(DataBucketBean.class).done().get();
		assertEquals(Optional.empty(), _index_service.getDataService().flatMap(s -> s.getWritableDataService(JsonNode.class, db1, Optional.empty(), Optional.empty())));
		
		final DataBucketBean db2 = BeanTemplateUtils.build(DataBucketBean.class).with("data_schema",
				BeanTemplateUtils.build(DataSchemaBean.class).done().get()
			).done().get();
		assertEquals(Optional.empty(), _index_service.getDataService().flatMap(s -> s.getWritableDataService(JsonNode.class, db2, Optional.empty(), Optional.empty())));
		
		final DataBucketBean db3 = BeanTemplateUtils.build(DataBucketBean.class).with("data_schema",
				BeanTemplateUtils.build(DataSchemaBean.class)
					.with("search_index_schema", 
							BeanTemplateUtils.build(DataSchemaBean.SearchIndexSchemaBean.class).with("enabled", false)
					.done().get()
				).done().get()
			).done().get();
		assertEquals(Optional.empty(), _index_service.getDataService().flatMap(s -> s.getWritableDataService(JsonNode.class, db3, Optional.empty(), Optional.empty())));
		
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	// INDEX MANAGEMENT
	
	@Test
	public void test_indexCreation() throws IOException {
		
		final Calendar time_setter = GregorianCalendar.getInstance();
		time_setter.set(2015, 1, 1, 13, 0, 0);
		
		final String bucket_str = Resources.toString(Resources.getResource("com/ikanow/aleph2/search_service/elasticsearch/services/test_bucket_validate_success.json"), Charsets.UTF_8);
		final DataBucketBean bucket = BeanTemplateUtils.build(bucket_str, DataBucketBean.class).with("modified", time_setter.getTime()).done().get();

		final String mapping_str = Resources.toString(Resources.getResource("com/ikanow/aleph2/search_service/elasticsearch/services/test_verbose_mapping_validate_results.json"), Charsets.UTF_8);
		final JsonNode mapping_json = _mapper.readTree(mapping_str.getBytes());		
		
		final String template_name = ElasticsearchIndexUtils.getBaseIndexName(bucket); 
		
		try {
			_crud_factory.getClient().admin().indices().prepareDeleteTemplate(template_name).execute().actionGet();
		}
		catch (Exception e) {} // (This is fine, just means it doesn't exist)
		
		// Create index template from empty
		
		{
			final GetIndexTemplatesRequest gt = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr = _crud_factory.getClient().admin().indices().getTemplates(gt).actionGet();
			assertTrue("No templates to start with", gtr.getIndexTemplates().isEmpty());
			
			_index_service.handlePotentiallyNewIndex(bucket, Optional.empty(), ElasticsearchIndexConfigUtils.buildConfigBeanFromSchema(bucket, _config_bean, _mapper), "_default_");
			
			final GetIndexTemplatesRequest gt2 = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr2 = _crud_factory.getClient().admin().indices().getTemplates(gt2).actionGet();
			assertEquals(1, _index_service._bucket_template_cache.size());
			assertEquals(1, gtr2.getIndexTemplates().size());
			
			assertTrue("Mappings should be equivalent", ElasticsearchIndexService.mappingsAreEquivalent(gtr2.getIndexTemplates().get(0), mapping_json, _mapper));
		}
		
		// Check is ignored subsequently (same date, same content; same date, different content)
		{
			
			_index_service.handlePotentiallyNewIndex(bucket, Optional.empty(), ElasticsearchIndexConfigUtils.buildConfigBeanFromSchema(bucket, _config_bean, _mapper), "_default_");
			
			final GetIndexTemplatesRequest gt2 = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr2 = _crud_factory.getClient().admin().indices().getTemplates(gt2).actionGet();
			assertEquals(1, _index_service._bucket_template_cache.size());
			assertEquals(1, gtr2.getIndexTemplates().size());
		}
		
		// Check is checked-but-left if time updated, content not
		{
			time_setter.set(2015, 1, 1, 14, 0, 0);
			final Date next_time = time_setter.getTime();
			final DataBucketBean bucket2 = BeanTemplateUtils.clone(bucket).with("modified", next_time).done();
			
			_index_service.handlePotentiallyNewIndex(bucket2, Optional.empty(), ElasticsearchIndexConfigUtils.buildConfigBeanFromSchema(bucket2, _config_bean, _mapper), "_default_");

			final GetIndexTemplatesRequest gt2 = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr2 = _crud_factory.getClient().admin().indices().getTemplates(gt2).actionGet();
			assertEquals(1, _index_service._bucket_template_cache.size());
			assertEquals(next_time, _index_service._bucket_template_cache.get(bucket._id()));
			assertEquals(1, gtr2.getIndexTemplates().size());
		}
		
		// Check is updated if time-and-content is different
		{
			time_setter.set(2015, 1, 1, 15, 0, 0);
			final String bucket_str2 = Resources.toString(Resources.getResource("com/ikanow/aleph2/search_service/elasticsearch/services/test_bucket2_validate_success.json"), Charsets.UTF_8);
			final DataBucketBean bucket2 = BeanTemplateUtils.build(bucket_str2, DataBucketBean.class).with("modified", time_setter.getTime()).done().get();
			
			_index_service.handlePotentiallyNewIndex(bucket2, Optional.empty(), ElasticsearchIndexConfigUtils.buildConfigBeanFromSchema(bucket2, _config_bean, _mapper), "_default_");
			
			final GetIndexTemplatesRequest gt2 = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr2 = _crud_factory.getClient().admin().indices().getTemplates(gt2).actionGet();
			assertEquals(1, _index_service._bucket_template_cache.size());
			assertEquals(time_setter.getTime(), _index_service._bucket_template_cache.get(bucket._id()));
			assertEquals(1, gtr2.getIndexTemplates().size());
			
			assertFalse(ElasticsearchIndexService.mappingsAreEquivalent(gtr2.getIndexTemplates().get(0), mapping_json, _mapper)); // has changed
		}
		
		// Check if mapping is deleted then next time bucket modified is updated then the mapping is recreated
		
		{
			_crud_factory.getClient().admin().indices().prepareDeleteTemplate(template_name).execute().actionGet();

			//(check with old date)
			
			final GetIndexTemplatesRequest gt = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr = _crud_factory.getClient().admin().indices().getTemplates(gt).actionGet();
			assertTrue("No templates to start with", gtr.getIndexTemplates().isEmpty());
			
			{
				_index_service.handlePotentiallyNewIndex(bucket, Optional.empty(), ElasticsearchIndexConfigUtils.buildConfigBeanFromSchema(bucket, _config_bean, _mapper), "_default_");
				
				final GetIndexTemplatesRequest gt2 = new GetIndexTemplatesRequest().names(template_name);
				final GetIndexTemplatesResponse gtr2 = _crud_factory.getClient().admin().indices().getTemplates(gt2).actionGet();
				assertTrue("Initially no change", gtr2.getIndexTemplates().isEmpty());
			}			
			
			// Update date and retry
			
			{
				time_setter.set(2015, 1, 1, 16, 0, 0);
				final Date next_time = time_setter.getTime();
				final DataBucketBean bucket2 = BeanTemplateUtils.clone(bucket).with("modified", next_time).done();				
				
				_index_service.handlePotentiallyNewIndex(bucket2, Optional.empty(), ElasticsearchIndexConfigUtils.buildConfigBeanFromSchema(bucket2, _config_bean, _mapper), "_default_");
				
				final GetIndexTemplatesRequest gt2 = new GetIndexTemplatesRequest().names(template_name);
				final GetIndexTemplatesResponse gtr2 = _crud_factory.getClient().admin().indices().getTemplates(gt2).actionGet();
				assertEquals(1, _index_service._bucket_template_cache.size());
				assertEquals(1, gtr2.getIndexTemplates().size());
				
				assertTrue("Mappings should be equivalent", ElasticsearchIndexService.mappingsAreEquivalent(gtr2.getIndexTemplates().get(0), mapping_json, _mapper));
			}
		}
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	// END-TO-END
	
	@Test
	public void test_handleMultiBucket() {
		// Will currently fail because read-only indices not yet supported
		
		final DataBucketBean multi_bucket = BeanTemplateUtils.build(DataBucketBean.class)
																		.with("_id", "test_multi_bucket")
																		.with("multi_bucket_children", ImmutableSet.builder().add("test1").build()).done().get();
		
		try {
			_index_service.getDataService().flatMap(s -> s.getWritableDataService(JsonNode.class, multi_bucket, Optional.empty(), Optional.empty()));
			fail("Should have thrown an exception");
		}
		catch (Exception e) {
			//(don't care about anything else here, this is mostly just for coverage at this point)
		}
	}
	
	// (including getCrudService)
	
	@Test
	public void test_endToEnd_autoTime() throws IOException, InterruptedException, ExecutionException {
		test_endToEnd_autoTime(true);
	}
	public void test_endToEnd_autoTime(boolean test_not_create_mode) throws IOException, InterruptedException, ExecutionException {
		final Calendar time_setter = GregorianCalendar.getInstance();
		time_setter.set(2015, 1, 1, 13, 0, 0);
		final String bucket_str = Resources.toString(Resources.getResource("com/ikanow/aleph2/search_service/elasticsearch/services/test_end_2_end_bucket.json"), Charsets.UTF_8);
		final DataBucketBean bucket = BeanTemplateUtils.build(bucket_str, DataBucketBean.class)
													.with("_id", "test_end_2_end")
													.with("full_name", "/test/end-end/auto-time")
													.with("modified", time_setter.getTime())
												.done().get();

		final String template_name = ElasticsearchIndexUtils.getBaseIndexName(bucket);
		
		// Check starting from clean
		
		{
			try {
				_crud_factory.getClient().admin().indices().prepareDeleteTemplate(template_name).execute().actionGet();
			}
			catch (Exception e) {} // (This is fine, just means it doesn't exist)		
			try {
				_crud_factory.getClient().admin().indices().prepareDelete(template_name + "*").execute().actionGet();
			}
			catch (Exception e) {} // (This is fine, just means it doesn't exist)		
			
			final GetIndexTemplatesRequest gt = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr = _crud_factory.getClient().admin().indices().getTemplates(gt).actionGet();
			assertTrue("No templates to start with", gtr.getIndexTemplates().isEmpty());
		}				
		
		final ICrudService<JsonNode> index_service_crud = 
				_index_service.getDataService()
					.flatMap(s -> s.getWritableDataService(JsonNode.class, bucket, Optional.empty(), Optional.empty()))
					.flatMap(IDataWriteService::getCrudService)
					.get();
		
		// Check template added:

		{
			final GetIndexTemplatesRequest gt2 = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr2 = _crud_factory.getClient().admin().indices().getTemplates(gt2).actionGet();
			assertEquals(1, _index_service._bucket_template_cache.size());
			assertEquals(1, gtr2.getIndexTemplates().size());
		}
		
		// Get batch sub-service
				
		@SuppressWarnings("unchecked")
		final Optional<ICrudService.IBatchSubservice<JsonNode>> batch_service = index_service_crud.getUnderlyingPlatformDriver(ICrudService.IBatchSubservice.class, Optional.empty())
																			.map(t -> (IBatchSubservice<JsonNode>)t);
		
		{
			assertTrue("Batch service must exist", batch_service.isPresent());
		}
			
		// Get information about the crud service
		
		final ElasticsearchContext es_context = (ElasticsearchContext) index_service_crud.getUnderlyingPlatformDriver(ElasticsearchContext.class, Optional.empty()).get();
		
		{
			assertTrue("Read write index", es_context instanceof ElasticsearchContext.ReadWriteContext);
			assertTrue("Temporal index", es_context.indexContext() instanceof ElasticsearchContext.IndexContext.ReadWriteIndexContext.TimedRwIndexContext);
			assertTrue("Auto type", es_context.typeContext() instanceof ElasticsearchContext.TypeContext.ReadWriteTypeContext.AutoRwTypeContext);
			
			// Check the the context contains the invalid 
			
			final ElasticsearchContext.TypeContext.ReadWriteTypeContext.AutoRwTypeContext context = (ElasticsearchContext.TypeContext.ReadWriteTypeContext.AutoRwTypeContext) es_context.typeContext();
			
			assertEquals(Arrays.asList("@timestamp"), context.fixed_type_fields().stream().collect(Collectors.toList()));
		}
		
		// Write some docs out
		
		Arrays.asList(1, 2, 3, 4, 5).stream()
						.map(i -> { time_setter.set(2015, i, 1, 13, 0, 0); return time_setter.getTime(); })
						.map(d -> (ObjectNode) _mapper.createObjectNode().put("@timestamp", d.getTime()))
						.forEach(o -> {
							ObjectNode o1 = o.deepCopy();
							o1.set("val1", _mapper.createObjectNode().put("val2", "test"));
							ObjectNode o2 = o.deepCopy();
							o2.put("val1", "test");
							batch_service.get().storeObject(o1, false);
							batch_service.get().storeObject(o2, false);
						});

		for (int i = 0; i < 30; ++i) {
			Thread.sleep(1000L);
			if (index_service_crud.countObjects().get() >= 10) {
				System.out.println("Test end 2 end: (Got all the records)");
				break;
			}
		}
		
		final GetMappingsResponse gmr = es_context.client().admin().indices().prepareGetMappings(template_name + "*").execute().actionGet();
		
		// Should have 5 different indexes, each with 2 types + _default_
		
		assertEquals(5, gmr.getMappings().keys().size());
		final Set<String> expected_keys =  Arrays.asList(1, 2, 3, 4, 5).stream().map(i -> template_name + "_2015-0" + (i+1) + "-01").collect(Collectors.toSet());
		final Set<String> expected_types =  Arrays.asList("_default_", "type_1", "type_2").stream().collect(Collectors.toSet());
		
		if (test_not_create_mode) StreamSupport.stream(gmr.getMappings().spliterator(), false)
			.forEach(x -> {
				assertTrue("Is one of the expected keys: " + x.key + " vs  " + expected_keys.stream().collect(Collectors.joining(":")), expected_keys.contains(x.key));
				//DEBUG
				//System.out.println(" ? " + x.key);
				StreamSupport.stream(x.value.spliterator(), false).forEach(Lambdas.wrap_consumer_u(y -> {
					//DEBUG
					//System.out.println("?? " + y.key + " --- " + y.value.sourceAsMap().toString());
					// Size 3: _default_, type1 and type2
					assertTrue("Is expected type: " + y.key, expected_types.contains(y.key));
				}));
				// Size 3: _default_, type_1, type_2 
				assertEquals("Should have 3 indexes: " + x.value.toString(), 3, x.value.size());
			});
		
		//TEST DELETION:
		if (test_not_create_mode) test_handleDeleteOrPurge(bucket, true);
	}
	
	@Test
	public void test_endToEnd_fixedFixed() throws IOException, InterruptedException, ExecutionException {
		final Calendar time_setter = GregorianCalendar.getInstance();
		time_setter.set(2015, 1, 1, 13, 0, 0);
		final String bucket_str = Resources.toString(Resources.getResource("com/ikanow/aleph2/search_service/elasticsearch/services/test_end_2_end_bucket2.json"), Charsets.UTF_8);
		final DataBucketBean bucket = BeanTemplateUtils.build(bucket_str, DataBucketBean.class)
													.with("_id", "2b_test_end_2_end")
													.with("full_name", "/test/end-end/fixed/fixed")
													.with("modified", time_setter.getTime())
												.done().get();

		final String template_name = ElasticsearchIndexUtils.getBaseIndexName(bucket);
		
		// Check starting from clean
		
		{
			try {
				_crud_factory.getClient().admin().indices().prepareDeleteTemplate(template_name).execute().actionGet();
			}
			catch (Exception e) {} // (This is fine, just means it doesn't exist)		
			try {
				_crud_factory.getClient().admin().indices().prepareDelete(template_name + "*").execute().actionGet();
			}
			catch (Exception e) {} // (This is fine, just means it doesn't exist)		
			
			final GetIndexTemplatesRequest gt = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr = _crud_factory.getClient().admin().indices().getTemplates(gt).actionGet();
			assertTrue("No templates to start with", gtr.getIndexTemplates().isEmpty());
		}				
		
		final ICrudService<JsonNode> index_service_crud = 
				_index_service.getDataService()
					.flatMap(s -> s.getWritableDataService(JsonNode.class, bucket, Optional.empty(), Optional.empty()))
					.flatMap(IDataWriteService::getCrudService)
					.get();
		
		// Check template added:

		{
			final GetIndexTemplatesRequest gt2 = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr2 = _crud_factory.getClient().admin().indices().getTemplates(gt2).actionGet();
			assertEquals(1, _index_service._bucket_template_cache.size());
			assertEquals(1, gtr2.getIndexTemplates().size());
		}
		
		// Get batch sub-service
				
		@SuppressWarnings("unchecked")
		final Optional<ICrudService.IBatchSubservice<JsonNode>> batch_service = index_service_crud.getUnderlyingPlatformDriver(ICrudService.IBatchSubservice.class, Optional.empty())
																			.map(t -> (IBatchSubservice<JsonNode>)t);
		
		{
			assertTrue("Batch service must exist", batch_service.isPresent());
		}
			
		// Get information about the crud service
		
		final ElasticsearchContext es_context = (ElasticsearchContext) index_service_crud.getUnderlyingPlatformDriver(ElasticsearchContext.class, Optional.empty()).get();
		
		{
			assertTrue("Read write index", es_context instanceof ElasticsearchContext.ReadWriteContext);
			assertTrue("Temporal index", es_context.indexContext() instanceof ElasticsearchContext.IndexContext.ReadWriteIndexContext.FixedRwIndexContext);
			assertTrue("Auto type", es_context.typeContext() instanceof ElasticsearchContext.TypeContext.ReadWriteTypeContext.FixedRwTypeContext);
		}
		
		// Write some docs out
		
		Arrays.asList(1, 2, 3, 4, 5).stream()
						.map(i -> { time_setter.set(2015, i, 1, 13, 0, 0); return time_setter.getTime(); })
						.map(d -> (ObjectNode) _mapper.createObjectNode().put("@timestamp", d.getTime()))
						.forEach(o -> {
							ObjectNode o1 = o.deepCopy();
							o1.put("val1", 10);
							ObjectNode o2 = o.deepCopy();
							o2.put("val1", "test");
							batch_service.get().storeObject(o1, false);
							batch_service.get().storeObject(o2, false);
						});

		//(give it a chance to run)
		Thread.sleep(5000L);
		
		final GetMappingsResponse gmr = es_context.client().admin().indices().prepareGetMappings(template_name + "*").execute().actionGet();
		
		// Should have 5 different indexes, each with 2 types + _default_
		
		assertEquals(1, gmr.getMappings().keys().size());
		final Set<String> expected_keys =  Arrays.asList("test_fixed_fixed__1cb6bdcdf44f").stream().collect(Collectors.toSet());
		final Set<String> expected_types =  Arrays.asList("data_object").stream().collect(Collectors.toSet());
		
		StreamSupport.stream(gmr.getMappings().spliterator(), false)
			.forEach(x -> {
				assertTrue("Is one of the expected keys: " + x.key + " vs  " + expected_keys.stream().collect(Collectors.joining(":")), expected_keys.contains(x.key));
				// Size 1: data_object 
				assertEquals(1, x.value.size());
				//DEBUG
				//System.out.println(" ? " + x.key);
				StreamSupport.stream(x.value.spliterator(), false).forEach(Lambdas.wrap_consumer_u(y -> {
					//DEBUG
					//System.out.println("?? " + y.key + " --- " + y.value.sourceAsMap().toString());
					assertTrue("Is expected type: " + y.key, expected_types.contains(y.key));
				}));
			});
		
		//TEST DELETION:
		test_handleDeleteOrPurge(bucket, false);
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	// TEST AGE OUT
	
	@Test
	public void test_ageOut() throws IOException, InterruptedException, ExecutionException {
		
		// Call test_endToEnd_autoTime to create 5 time based indexes
		// 2015-01-01 -> 2015-05-01
		// How far is now from 2015-05-03
		final Date d = TimeUtils.getDateFromSuffix("2015-03-02").success();
		final long total_time_ms = new Date().getTime() - d.getTime();
		final long total_days = total_time_ms/(1000L*3600L*24L);
		final String age_out = ErrorUtils.get("{0} days", total_days);
		
		final DataBucketBean bucket = BeanTemplateUtils.build(DataBucketBean.class)
										.with("full_name", "/test/end-end/auto-time")
										.with(DataBucketBean::data_schema,
												BeanTemplateUtils.build(DataSchemaBean.class)
													.with(DataSchemaBean::temporal_schema,
														BeanTemplateUtils.build(TemporalSchemaBean.class)
															.with(TemporalSchemaBean::exist_age_max, age_out)
														.done().get()
													)
												.done().get()
												)
										.done().get();
	
		final String template_name = ElasticsearchIndexUtils.getBaseIndexName(bucket);
		
		test_endToEnd_autoTime(false);

		_index_service._crud_factory.getClient().admin().indices().prepareCreate(template_name + "_2015-03-01_1").execute().actionGet();
		
		final GetMappingsResponse gmr = _index_service._crud_factory.getClient().admin().indices().prepareGetMappings(template_name + "*").execute().actionGet();
		assertEquals(6, gmr.getMappings().keys().size());
		
		CompletableFuture<BasicMessageBean> cf = _index_service.getDataService().get().handleAgeOutRequest(bucket);
		
		BasicMessageBean res = cf.get();
		
		assertEquals(true, res.success());
		assertTrue("sensible message: " + res.message(), res.message().contains(" 2 "));

		assertTrue("Message marked as loggable: " + res.details(), Optional.ofNullable(res.details()).filter(m -> m.containsKey("loggable")).isPresent());
		
		System.out.println("Return from to delete: " + res.message());
		
		Thread.sleep(5000L); // give the indexes time to delete
		
		final GetMappingsResponse gmr2 = _index_service._crud_factory.getClient().admin().indices().prepareGetMappings(template_name + "*").execute().actionGet();
		assertEquals(3, gmr2.getMappings().keys().size());
		
		// Check some edge cases:

		// 1) Run it again, returns success but not loggable:
		
		CompletableFuture<BasicMessageBean> cf2 = _index_service.getDataService().get().handleAgeOutRequest(bucket);
		
		BasicMessageBean res2 = cf2.get();
		
		assertEquals(true, res2.success());
		assertTrue("sensible message: " + res2.message(), res2.message().contains(" 0 "));
		assertTrue("Message _not_ marked as loggable: " + res2.details(), !Optional.ofNullable(res2.details()).map(m -> m.get("loggable")).isPresent());
				
		// 2) No temporal settings
		
		final DataBucketBean bucket3 = BeanTemplateUtils.build(DataBucketBean.class)
				.with("full_name", "/test/handle/age/out/delete/not/temporal")
				.with(DataBucketBean::data_schema,
						BeanTemplateUtils.build(DataSchemaBean.class)
						.done().get())
				.done().get();
		
		CompletableFuture<BasicMessageBean> cf3 = _index_service.getDataService().get().handleAgeOutRequest(bucket3);		
		BasicMessageBean res3 = cf3.get();
		// no temporal settings => returns success
		assertEquals(true, res3.success());
		
		// 3) Unparseable temporal settings (in theory won't validate but we can test here)

		final DataBucketBean bucket4 = BeanTemplateUtils.build(DataBucketBean.class)
				.with("full_name", "/test/handle/age/out/delete/temporal/malformed")
				.with(DataBucketBean::data_schema,
						BeanTemplateUtils.build(DataSchemaBean.class)
							.with(DataSchemaBean::temporal_schema,
								BeanTemplateUtils.build(TemporalSchemaBean.class)
									.with(TemporalSchemaBean::exist_age_max, "bananas")
								.done().get()
							)
						.done().get())
				.done().get();
		
		CompletableFuture<BasicMessageBean> cf4 = _index_service.getDataService().get().handleAgeOutRequest(bucket4);		
		BasicMessageBean res4 = cf4.get();
		// no temporal settings => returns success
		assertEquals(false, res4.success());
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	// TEST DELETION
	
	// (these are called from the code above)
	
	public void test_handleDeleteOrPurge(final DataBucketBean to_handle, boolean delete_not_purge) throws InterruptedException, ExecutionException {
		System.out.println("****** Checking delete/purge");
		
		final String template_name = ElasticsearchIndexUtils.getBaseIndexName(to_handle);
		final ICrudService<JsonNode> index_service_crud = 
				_index_service.getDataService()
					.flatMap(s -> s.getWritableDataService(JsonNode.class, to_handle, Optional.empty(), Optional.empty()))
					.flatMap(IDataWriteService::getCrudService)
					.get();
		
		final ElasticsearchContext es_context = (ElasticsearchContext) index_service_crud.getUnderlyingPlatformDriver(ElasticsearchContext.class, Optional.empty()).get();
		
		// (Actually first off, check there's data and templates)
		// Data:
		{
			final GetMappingsResponse gmr = es_context.client().admin().indices().prepareGetMappings(template_name + "*").execute().actionGet();
			assertTrue("There are indexes", gmr.getMappings().keys().size() > 0);			
		}
		// Templates:
		{
			final GetIndexTemplatesRequest gt_pre = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr_pre = _crud_factory.getClient().admin().indices().getTemplates(gt_pre).actionGet();
			assertEquals(1, _index_service._bucket_template_cache.size());
			assertEquals(1, gtr_pre.getIndexTemplates().size());			
		}
		
		// Then, perform request
		final BasicMessageBean result = _index_service.getDataService().get().handleBucketDeletionRequest(to_handle, Optional.empty(), delete_not_purge).get();
		assertEquals("Deletion should succeed: " + result.message(), true, result.success());
		
		// Check templates gone iff deleting not purging
				
		if (delete_not_purge) {
			final GetIndexTemplatesRequest gt = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr = _crud_factory.getClient().admin().indices().getTemplates(gt).actionGet();
			assertTrue("No templates after deletion", gtr.getIndexTemplates().isEmpty());			
		}
		else {
			final GetIndexTemplatesRequest gt2 = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr2 = _crud_factory.getClient().admin().indices().getTemplates(gt2).actionGet();
			assertEquals(1, _index_service._bucket_template_cache.size());
			assertEquals(1, gtr2.getIndexTemplates().size());			
		}
		
		// Check all files deleted

		// Check via mappings
		{
			final GetMappingsResponse gmr = es_context.client().admin().indices().prepareGetMappings(template_name + "*").execute().actionGet();
			assertEquals(0, gmr.getMappings().keys().size());						
		}
		// Check via index size (recreates templates)

		final ICrudService<JsonNode> index_service_crud_2 = 
				_index_service.getDataService()
					.flatMap(s -> s.getWritableDataService(JsonNode.class, to_handle, Optional.empty(), Optional.empty()))
					.flatMap(IDataWriteService::getCrudService)
					.get();
		
		assertEquals(0, index_service_crud_2.countObjects().get().intValue());
	}

	@Test
	public void test_deleteNonexistantBucket() throws JsonParseException, JsonMappingException, IOException, InterruptedException, ExecutionException {
		final DataBucketBean bucket = BeanTemplateUtils.build(DataBucketBean.class)
													.with("_id", "2b_test_end_2_end_not_exist")
													.with("full_name", "/test/end-end/fixed/fixed/not/exist")
												.done().get();
	
				
		final BasicMessageBean result = _index_service.getDataService().get().handleBucketDeletionRequest(bucket, Optional.empty(), true).get();
		assertEquals("Deletion should succeed: " + result.message(), true, result.success());
	}
}
