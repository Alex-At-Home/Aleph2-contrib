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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MockBeJobService implements IBeJobService {
	private static final Logger logger = LogManager.getLogger();

	@Override
	public String runEnhancementJob(String bucketFullName, String bucketPathStr, String enrichmentControlName) {
		logger.debug("runEnhancementJob:"+bucketFullName+",bucketPathStr:"+bucketPathStr+",enrichmentControlName:"+enrichmentControlName);
		return bucketFullName;
	}

}
