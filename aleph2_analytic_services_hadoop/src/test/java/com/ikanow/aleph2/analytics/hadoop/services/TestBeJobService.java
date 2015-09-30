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
package com.ikanow.aleph2.analytics.hadoop.services;

import static org.junit.Assert.*;

import java.io.File;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Optional;

import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.mapreduce.Job;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.ikanow.aleph2.analytics.hadoop.services.BatchEnrichmentContext;
import com.ikanow.aleph2.analytics.hadoop.services.BeJobLauncher;
import com.ikanow.aleph2.analytics.hadoop.services.BeJobLoader;
import com.ikanow.aleph2.core.shared.utils.DirUtils;
import com.ikanow.aleph2.data_model.interfaces.data_services.IStorageService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IDataWriteService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IServiceContext;
import com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadBean;
import com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadJobBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean;
import com.ikanow.aleph2.data_model.objects.shared.GlobalPropertiesBean;
import com.ikanow.aleph2.data_model.objects.shared.SharedLibraryBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.ErrorUtils;
import com.ikanow.aleph2.data_model.utils.ModuleUtils;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;

import fj.Unit;
import fj.data.Validation;

public class TestBeJobService {
    private static final Logger logger = LogManager.getLogger(TestBeJobService.class);

	@Inject
	protected GlobalPropertiesBean _globals = null;

	@Inject
	protected IServiceContext _service_context = null;
	
	@Before
	public void setupDependencies() throws Exception {
		try{
			final String temp_dir = System.getProperty("java.io.tmpdir");

			// OK we're going to use guice, it was too painful doing this by hand...				
			Config config = ConfigFactory.parseReader(new InputStreamReader(this.getClass().getResourceAsStream("/context_local_test.properties")))
					.withValue("globals.local_root_dir", ConfigValueFactory.fromAnyRef(temp_dir))
					.withValue("globals.local_cached_jar_dir", ConfigValueFactory.fromAnyRef(temp_dir))
					.withValue("globals.distributed_root_dir", ConfigValueFactory.fromAnyRef(temp_dir))
					.withValue("globals.local_yarn_config_dir", ConfigValueFactory.fromAnyRef(temp_dir));

			Injector app_injector = ModuleUtils.createTestInjector(Arrays.asList(), Optional.of(config));	
			app_injector.injectMembers(this);

		}
		catch (Throwable t) {
			System.out.println(ErrorUtils.getLongForm("{0}", t));
			throw t; 
		}
	} // setup dependencies

	@Test
	public void testBeJobService() throws Exception{
		// Only run this test on linux systems, Hadoop local mode not reliably working on Windows
		if (File.separator.equals("\\")) { // windows mode!
			logger.info("WINDOWS MODE SKIPPING THIS TEST");
		}
		else {
			final MockAnalyticsContext analytics_context = new MockAnalyticsContext(_service_context);
			final BatchEnrichmentContext batch_context = new BatchEnrichmentContext(analytics_context);
			
			// Set passthrough topology
			final AnalyticThreadJobBean test_analytic = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
					.with(AnalyticThreadJobBean::name, "simplejob")
				.done().get();
			
			final DataBucketBean test_bucket = BeanTemplateUtils.build(DataBucketBean.class)
													.with(DataBucketBean::full_name, "/test/simple/analytics")
													.with(DataBucketBean::analytic_thread,
															BeanTemplateUtils.build(AnalyticThreadBean.class)
																.with(AnalyticThreadBean::jobs, Arrays.asList(test_analytic))
															.done().get()
															)
													.with(DataBucketBean::data_schema,
															BeanTemplateUtils.build(DataSchemaBean.class)
																.with(DataSchemaBean::search_index_schema,
																	BeanTemplateUtils.build(DataSchemaBean.SearchIndexSchemaBean.class).done().get()
																)
															.done().get()
															)
												.done().get();
			
			final SharedLibraryBean technology = BeanTemplateUtils.build(SharedLibraryBean.class).with(SharedLibraryBean::path_name, "/tech/test").done().get();
			final SharedLibraryBean passthrough = BeanTemplateUtils.build(SharedLibraryBean.class).with(SharedLibraryBean::path_name, "/module/test").done().get();
			analytics_context.setTechnologyConfig(technology);
			analytics_context.setModuleConfig(passthrough);
			analytics_context.setBucket(test_bucket);
			batch_context.setJob(test_analytic);
			
			// Set up directory:
			createFolderStructure(test_bucket);
			assertTrue("File1 should exist", new File(_globals.distributed_root_dir()+test_bucket.full_name() + IStorageService.TO_IMPORT_DATA_SUFFIX + "bucket1data.txt").exists());
			assertTrue("File2 should exist", new File(_globals.distributed_root_dir()+test_bucket.full_name() + IStorageService.TO_IMPORT_DATA_SUFFIX + "bucket1data.json").exists());
			// (Clear ES index)
			final IDataWriteService<JsonNode> es_index = 
					_service_context.getSearchIndexService().get().getDataService().get().getWritableDataService(JsonNode.class, test_bucket, Optional.empty(), Optional.empty()).get();
			es_index.deleteDatastore().get();
			Thread.sleep(1000L);
			assertEquals(0, es_index.countObjects().get().intValue());
			
			final BeJobLauncher beJobService = new BeJobLauncher(_globals, new BeJobLoader(batch_context), batch_context);		

			final Validation<String, Job> result = beJobService.runEnhancementJob(test_bucket, "simplemodule");
			
			result.validation(
					fail -> { logger.info("Launch FAIL " + fail); return Unit.unit(); }
					,
					success -> { logger.info("Launched " + success.getJobName()); return Unit.unit(); }
					);

			
			assertTrue("Job worked: " + result.f().toOption().orSome(""), result.isSuccess());
			
			for (int ii = 0; (ii < 60) && !result.success().isComplete(); ++ii) {
				Thread.sleep(1000L);
			}
			logger.info("Job has finished: " + result.success().isSuccessful());
			assertTrue("Job successful", result.success().isSuccessful());
			
			// Check the processed  file has vanished
			
			assertFalse("File1 shouldn't exist", new File(_globals.distributed_root_dir()+test_bucket.full_name() + IStorageService.TO_IMPORT_DATA_SUFFIX + "bucket1data.txt").exists());
			assertFalse("File2 shouldn't exist", new File(_globals.distributed_root_dir()+test_bucket.full_name() + IStorageService.TO_IMPORT_DATA_SUFFIX + "bucket1data.json").exists());
			
			// Check the object got written into ES
			for (int ii = 0; ii < 12; ++ii) {
				Thread.sleep(500L);
				if (es_index.countObjects().get().intValue() >= 2) {
					break;
				}
			}			
			assertEquals(2, es_index.countObjects().get().intValue());			
		}
	}
	
	//////////////////////////////////////////////
	
	// UTILS:
	
	protected void createFolderStructure(final DataBucketBean bucket){
		// create folder structure if it does not exist for testing.		
		final FileContext fileContext = _service_context.getStorageService().getUnderlyingPlatformDriver(FileContext.class,Optional.empty()).get();
		logger.info("Root dir:"+_globals.distributed_root_dir());
		final String bucketPath1 = _globals.distributed_root_dir() + bucket.full_name();
		final String bucketReadyPath1 = bucketPath1+"/managed_bucket/import/ready";
		DirUtils.createDirectory(fileContext,bucketReadyPath1);
		DirUtils.createDirectory(fileContext,_globals.distributed_root_dir()+"/data/misc/bucket2/managed_bucket/import/ready");
		DirUtils.createDirectory(fileContext,_globals.distributed_root_dir()+"/data/misc/bucket3/managed_bucket/import/ready");
		StringBuffer sb = new StringBuffer();
		sb.append("bucket1data\r\n");
		DirUtils.createUTF8File(fileContext,bucketReadyPath1+"/bucket1data.txt", sb);
		StringBuffer sb2 = new StringBuffer();
		sb2.append("{\"testField\":\"test1\"}");
		DirUtils.createUTF8File(fileContext,bucketReadyPath1+"/bucket1data.json", sb2);
	}
	
	
}