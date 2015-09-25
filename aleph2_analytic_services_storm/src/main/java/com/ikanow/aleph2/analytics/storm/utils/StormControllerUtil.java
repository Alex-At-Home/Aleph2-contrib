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
package com.ikanow.aleph2.analytics.storm.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.Yaml;

import backtype.storm.generated.AlreadyAliveException;
import backtype.storm.generated.StormTopology;
import backtype.storm.generated.TopologyInfo;

import com.google.common.collect.Sets;
import com.ikanow.aleph2.analytics.storm.data_model.IStormController;
import com.ikanow.aleph2.analytics.storm.services.LocalStormController;
import com.ikanow.aleph2.analytics.storm.services.RemoteStormController;
import com.ikanow.aleph2.core.shared.utils.JarBuilderUtil;
import com.ikanow.aleph2.core.shared.utils.LiveInjector;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.shared.BasicMessageBean;
import com.ikanow.aleph2.data_model.objects.shared.GlobalPropertiesBean;
import com.ikanow.aleph2.data_model.utils.Lambdas;
import com.ikanow.aleph2.data_model.utils.ModuleUtils;

/**
 * Factory for returning a local or remote storm controller.
 * 
 * Also contains static functions for using that cluster to perform various actions
 * 
 * @author Burch
 *
 */
public class StormControllerUtil {
	private static final Logger _logger = LogManager.getLogger();
	private final static Set<String> dirs_to_ignore = Sets.newHashSet("org/slf4j", "org/apache/log4j");
	protected final static ConcurrentHashMap<String, Date> storm_topology_jars_cache = new ConcurrentHashMap<>();
	protected final static long MAX_RETRIES = 60; //60 retries at 1s == 1m max retry time
	
	/**
	 * Returns an instance of a local storm controller.
	 * 
	 * @return
	 */
	public static IStormController getLocalStormController() {
		return new LocalStormController();
	}
	
	/**
	 * Returns an instance of a remote storm controller pointed at the given nimbus server
	 * for storm_thrift_transport_plugin we typically use "backtype.storm.security.auth.SimpleTransportPlugin"
	 * 
	 * @param nimbus_host
	 * @param nimbus_thrift_port
	 * @param storm_thrift_transport_plugin
	 * @return
	 */
	public static IStormController getRemoteStormController(String nimbus_host, int nimbus_thrift_port, String storm_thrift_transport_plugin) {		
		return new RemoteStormController(nimbus_host, nimbus_thrift_port, storm_thrift_transport_plugin);
	}
	
	/**
	 * Returns an instance of a remote storm controller pointed at the given config
	 * 
	 * @param config
	 * @return
	 */
	public static IStormController getRemoteStormController(Map<String, Object> config) {
		return new RemoteStormController(config);
	}
	
	/**
	 * Submits a job on given storm cluster.  When submitting to a local cluster, input_jar_location
	 * can be null (it won't be used).  When submitting remotely it should be the local file path
	 * where the jar to be submitted is located.
	 * 
	 * To check the status of a job call getJobStats
	 * 
	 * @param storm_controller
	 * @param job_name
	 * @param input_jar_location
	 * @param topology
	 * @throws Exception
	 */
	public static void submitJob(IStormController storm_controller, String job_name, String input_jar_location, StormTopology topology, Map<String, Object> config_override) throws Exception {				
		storm_controller.submitJob(job_name, input_jar_location, topology, config_override);
	}
	
	/**
	 * Should stop a job on the storm cluster given the job_name, status of the stop can
	 * be checked via getJobStats
	 * 
	 * @param storm_controller
	 * @param job_name
	 * @throws Exception
	 */
	public static void stopJob(IStormController storm_controller, String job_name) throws Exception {
		storm_controller.stopJob(job_name);
	}
	
	/**
	 * Should return the job statistics for a job running on the storm cluster with the given job_name
	 * 
	 * @param storm_controller
	 * @param job_name
	 * @return
	 * @throws Exception
	 */
	public static TopologyInfo getJobStats(IStormController storm_controller, String job_name) throws Exception {
		return storm_controller.getJobStats(job_name);
	}
	
	/**
	 * Util function to create a storm jar in a random temp location.  This
	 * can be used for creating the jar to submit to submitJob.
	 * 
	 * Note: you do not want to pass the storm library to this function if you
	 * intend to use it to submit a storm job, storm jobs cannot contain that library.
	 * 
	 * Also note: JarBuilderUtil merges files in order, so if jars_to_merge[0] contains
	 * a file in the same location as jars_to_merge[1], only jars_to_merge[0] will exist in
	 * the final jar.
	 * 
	 * @param jars_to_merge
	 * @param jar_location location of the jar to send
	 * @return
	 */
	public static boolean buildStormTopologyJar(final Collection<String> jars_to_merge, final String input_jar_location) {
		try {				
			_logger.debug("creating jar to submit at: " + input_jar_location);
			//final String input_jar_location = System.getProperty("java.io.tmpdir") + File.separator + UuidUtils.get().getTimeBasedUuid() + ".jar";
			JarBuilderUtil.mergeJars(jars_to_merge, input_jar_location, dirs_to_ignore);
			return true;
		} catch (Exception e) {
			_logger.error(ErrorUtils.getLongForm("Error building storm jar {0}", e));
			return false;
		}			
	}
	
	/**
	 * Returns a remote storm controller given a yarn config directory.  Will look for
	 * the storm config at yarn_config_dir/storm.properties
	 * 
	 * @param yarn_config_dir
	 * @return
	 * @throws FileNotFoundException 
	 */
	public static IStormController getStormControllerFromYarnConfig(String yarn_config_dir) throws FileNotFoundException {
		Yaml yaml = new Yaml();
		InputStream input = new FileInputStream(new File(yarn_config_dir + File.separator + "storm.yaml"));		
		@SuppressWarnings("unchecked")
		Map<String, Object> object = (Map<String, Object>) yaml.load(input);
		IStormController storm = getRemoteStormController(object);
		return storm;	
	}	
	
	/**
	 * Starts up a storm job.
	 * 
	 * 1. gets the storm instance from the yarn config
	 * 2. Makes a mega jar consisting of:
	 * 	A. Underlying artefacts (system libs)
	 *  B. User supplied libraries
	 * 3. Submit megajar to storm with jobname of the bucket id
	 * 
	 * @param bucket
	 * @param underlying_artefacts
	 * @param yarn_config_dir
	 * @param user_lib_paths
	 * @param topology
	 * @return
	 */
	public static CompletableFuture<BasicMessageBean> startJob(
														final IStormController storm_controller, 
														final DataBucketBean bucket, 
														final Collection<Object> underlying_artefacts, 
														final Collection<String> user_lib_paths, 
														final StormTopology topology, 
														final Map<String, String> config, 
														final String cached_jar_dir)
	{
		if (null == topology) {
			return CompletableFuture.completedFuture(
					ErrorUtils.buildErrorMessage(StormControllerUtil.class, "startJob", ErrorUtils.TOPOLOGY_NULL_ERROR, bucket.full_name())
					 );
		}
		
		_logger.info("Retrieved user Storm config topology: spouts=" + topology.get_spouts_size() + " bolts=" + topology.get_bolts_size() + " configs=" + config.toString());
		
		final Set<String> jars_to_merge = new TreeSet<String>();
		
		final CompletableFuture<String> jar_future = Lambdas.get(() -> {
			if (RemoteStormController.class.isAssignableFrom(storm_controller.getClass())) {
				// (This is only necessary in the remote case)
				
				jars_to_merge.addAll( underlying_artefacts.stream()
						.map(artefact -> LiveInjector.findPathJar(artefact.getClass(), ""))
						.filter(f -> !f.equals(""))
						.collect(Collectors.toSet()));
				
				if (jars_to_merge.isEmpty()) { // special case: no aleph2 libs found, this is almost certainly because this is being run from eclipse...
					final GlobalPropertiesBean globals = ModuleUtils.getGlobalProperties();
					_logger.warn("WARNING: no library files found, probably because this is running from an IDE - instead taking all JARs from: " + (globals.local_root_dir() + "/lib/"));
					try {
						//... and LiveInjecter doesn't work on classes ... as a backup just copy everything from "<LOCAL_ALEPH2_HOME>/lib" into there 
						jars_to_merge.addAll(
								FileUtils.listFiles(new File(globals.local_root_dir() + "/lib/"), new String[] { "jar" }, false)
									.stream()
									.map(File::toString)
									.collect(Collectors.toList())
									);
					}
					catch (Exception e) {
						throw new RuntimeException("In eclipse/IDE mode, directory not found: " + (globals.local_root_dir() + "/lib/"));
					}
				}
				//add in the user libs
				jars_to_merge.addAll(user_lib_paths);
				
				//create jar
				return buildOrReturnCachedStormTopologyJar(jars_to_merge, cached_jar_dir);		
			}
			else {
				return CompletableFuture.completedFuture("/unused/dummy.jar");
			}
		});
		
		//submit to storm
		@SuppressWarnings("unchecked")
		final CompletableFuture<BasicMessageBean> submit_future = Lambdas.get(() -> {
			long retries = 0;			
			while ( retries < MAX_RETRIES ) {				
				try {
					_logger.debug("Trying to submit job, try: " + retries + " of " + MAX_RETRIES);
					final String jar_file_location = jar_future.get();
					return storm_controller.submitJob(bucketPathToTopologyName(bucket.full_name()), jar_file_location, topology, (Map<String, Object>)(Map<String, ?>)config);
				} 
				catch ( Exception ex) {
					if ( ex instanceof AlreadyAliveException ) {
						retries++;
						//sleep 1s, was seeing about 2s of sleep required before job successfully submitted on restart
						try {
							Thread.sleep(1000);
						} catch (Exception e) {
							final CompletableFuture<BasicMessageBean> error_future = new CompletableFuture<BasicMessageBean>();
							error_future.completeExceptionally(e);
							return error_future;
						} 
					} else {
						retries = MAX_RETRIES; //we threw some other exception, bail out
						final CompletableFuture<BasicMessageBean> error_future = new CompletableFuture<BasicMessageBean>();
						error_future.completeExceptionally(ex);
						return error_future;
					}
				}
			}
			//we maxed out our retries, throw failure
			final CompletableFuture<BasicMessageBean> error_future = new CompletableFuture<BasicMessageBean>();
			error_future.completeExceptionally(new Exception("Error submitting job, ran out of retries (previous (same name) job is probably still alive)"));
			return error_future;
		});
		return submit_future;		
	}
	
	/**
	 * Checks the jar cache to see if an entry already exists for this list of jars,
	 * returns the path of that entry if it does exist, otherwise creates the jar, adds
	 * the path to the cache and returns it.
	 * 
	 * @param jars_to_merge
	 * @return
	 * @throws Exception 
	 */
	public static synchronized CompletableFuture<String> buildOrReturnCachedStormTopologyJar(final Collection<String> jars_to_merge, final String cached_jar_dir) {
		CompletableFuture<String> future = new CompletableFuture<String>();
		final String hashed_jar_name = JarBuilderUtil.getHashedJarName(jars_to_merge, cached_jar_dir);
		//1. Check cache for this jar via hash of jar names
		if ( storm_topology_jars_cache.containsKey(hashed_jar_name)) {
			//if exists:
			//2. validate jars has not been updated
			Date most_recent_update = JarBuilderUtil.getMostRecentlyUpdatedFile(jars_to_merge);
			//if the cache is more recent than any of the files, we assume nothing has been updated
			if ( storm_topology_jars_cache.get(hashed_jar_name).getTime() > most_recent_update.getTime() ) {
				//RETURN return cached jar file path
				_logger.debug("Returning a cached copy of the jar");
				//update the cache copy to set its modified time to now so we don't clean it up
				JarBuilderUtil.updateJarModifiedTime(hashed_jar_name);				
				future.complete(hashed_jar_name);
				return future;
			} else {
				//delete cache copy
				_logger.debug("Removing an expired cached copy of the jar");
				removeCachedJar(hashed_jar_name);
			}
		}
				
		//if we fall through
		//3. create jar
		_logger.debug("Fell through or cache copy is old, have to create a new version");
		if ( buildStormTopologyJar(jars_to_merge, hashed_jar_name) ) {		
			//4. add jar to cache w/ current/newest file timestamp		
			storm_topology_jars_cache.put(hashed_jar_name, new Date());
			//RETURN return new jar file path
			future.complete(hashed_jar_name);
		} else {
			//had an error creating jar, throw an exception?
			future.completeExceptionally(new Exception("Error trying to create storm jar, see logs"));
		}
		return future;
		
	}
	
	/**
	 * Remove the give file from cache and locally if it exists
	 * @param hashed_jar_name
	 */
	private static void removeCachedJar(String hashed_jar_name) {
		storm_topology_jars_cache.remove(hashed_jar_name);
		File hashed_file = new File(hashed_jar_name);
		hashed_file.exists();
		hashed_file.delete();
	}

	/**
	 * Stops a storm job, uses the bucket.id to try and find the job to stop
	 * 
	 * @param bucket
	 * @return
	 */
	public static CompletableFuture<BasicMessageBean> stopJob(IStormController storm_controller, DataBucketBean bucket) {
		CompletableFuture<BasicMessageBean> stop_future = new CompletableFuture<BasicMessageBean>();
		try {
			storm_controller.stopJob(bucketPathToTopologyName(bucket.full_name()));
		} catch (Exception ex) {
			stop_future.complete(
					ErrorUtils.buildErrorMessage(StormControllerUtil.class, "stopJob", ErrorUtils.getLongForm("Error stopping storm job: {0}", ex))
						);
			return stop_future;
		}
		stop_future.complete(ErrorUtils.buildSuccessMessage(StormControllerUtil.class, "stopJob", "Stopped storm job succesfully"));
		return stop_future;
	}

	/**
	 * Restarts a storm job by first calling stop, then calling start
	 * 
	 * @param bucket
	 * @param underlying_artefacts
	 * @param yarn_config_dir
	 * @param user_lib_paths
	 * @param topology
	 * @return
	 */
	public static CompletableFuture<BasicMessageBean> restartJob(
														final IStormController storm_controller, 
														final DataBucketBean bucket, 
														final Collection<Object> underlying_artefacts, 
														final Collection<String> user_lib_paths, 
														final StormTopology topology, 
														final Map<String, String> config, 
														final String cached_jar_dir)
	{
		CompletableFuture<BasicMessageBean> stop_future = stopJob(storm_controller, bucket);
		try {
			stop_future.get(5, TimeUnit.SECONDS);
			waitForJobToDie(storm_controller, bucket, 15L);
		} catch (Exception e) {
			CompletableFuture<BasicMessageBean> error_future = new CompletableFuture<BasicMessageBean>();
			error_future.complete(
					ErrorUtils.buildErrorMessage(StormControllerUtil.class, "restartJob", ErrorUtils.getLongForm("Error stopping storm job: {0}", e))
					);
			return error_future;
		}
		return startJob(storm_controller, bucket, underlying_artefacts, user_lib_paths, topology, config, cached_jar_dir);
	}
	
	/**
	 * Continually checks if job has died, returns true if it has, or throws an exception if
	 * timeout occurs (seconds_to_wait elaspses).
	 * 
	 * @param storm_controller
	 * @param bucket
	 * @param l
	 * @return
	 * @throws Exception 
	 */
	public static void waitForJobToDie(
			IStormController storm_controller, DataBucketBean bucket, long seconds_to_wait) throws Exception {
		long start_time = System.currentTimeMillis();
		long num_tries = 0;
		long expire_time = System.currentTimeMillis() + (seconds_to_wait*1000);
		while ( System.currentTimeMillis() < expire_time ) {
			TopologyInfo info = null;
			try {
				info = getJobStats(storm_controller, bucketPathToTopologyName(bucket.full_name()));
			} catch (Exception ex) {}
			if ( null == info ) {				
				_logger.debug("JOB_STATUS: no longer exists, assuming that job is dead and gone, spent: " + (System.currentTimeMillis()-start_time) + "ms waiting");				
				return;
			}
			num_tries++;
			_logger.debug("Waiting for job status to go away, try number: " + num_tries);
			Thread.sleep(2000); //wait 2s between checks, in tests it was taking 8s to clear
		}		
	}
	
	//TODO: oh noes! this is all basked on the bucket, which is not correct because we can have jobs within a bucket... currently they get the same name
	
	/**
	 * Converts a buckets path to a use-able topology name
	 * 1 way conversion, ie can't convert back
	 * Topology name cannot contain any of the following: #{"." "/" ":" "\\"})
	 * . / : \
	 * 
	 * @param bucket_path
	 * @return
	 */
	public static String bucketPathToTopologyName(final String bucket_path) {
		return bucket_path
				.replaceFirst("^/", "") // "/" -> "-"
				.replaceAll("[-]", "_") 
				.replaceAll("[/]", "-")
				.replaceAll("[^a-zA-Z0-9_-]", "_")
				.replaceAll("__+", "_") // (don't allow __ anywhere except at the end)
				+ "__"; // (always end in __) 
	}	
}
