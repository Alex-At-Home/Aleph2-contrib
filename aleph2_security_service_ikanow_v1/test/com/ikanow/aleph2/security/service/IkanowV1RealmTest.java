package com.ikanow.aleph2.security.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Optional;

import org.apache.shiro.authc.UsernamePasswordToken;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ISecurityService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IServiceContext;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ISubject;
import com.ikanow.aleph2.data_model.utils.ModuleUtils;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;

public class IkanowV1RealmTest {
	protected Config config = null;

	@Inject
	protected IServiceContext _service_context = null;

	protected IManagementDbService _management_db;
	protected ISecurityService securityService = null;

	@Before
	public void setupDependencies() throws Exception {
		try {
			
		if (_service_context != null) {
			return;
		}

		final String temp_dir = System.getProperty("java.io.tmpdir");

		// OK we're going to use guice, it was too painful doing this by hand...
		config = ConfigFactory.parseReader(new InputStreamReader(this.getClass().getResourceAsStream("/test_security_service_v1.properties")))
				.withValue("globals.local_root_dir", ConfigValueFactory.fromAnyRef(temp_dir))
				.withValue("globals.local_cached_jar_dir", ConfigValueFactory.fromAnyRef(temp_dir))
				.withValue("globals.distributed_root_dir", ConfigValueFactory.fromAnyRef(temp_dir))
				.withValue("globals.local_yarn_config_dir", ConfigValueFactory.fromAnyRef(temp_dir));

		Injector app_injector = ModuleUtils.createInjector(Arrays.asList(), Optional.of(config));	
		app_injector.injectMembers(this);
		this._management_db = _service_context.getCoreManagementDbService();
		this.securityService =  _service_context.getSecurityService();
		} catch(Throwable e) {
			
			e.printStackTrace();
		}
	}

	
	@Test
	@Ignore
	public void testAuthenticated() {
		ISubject subject = securityService.getSubject();
		assertNotNull(subject);
        UsernamePasswordToken token = new UsernamePasswordToken("jfreydank@ikanow.com", "nogoodpassword");
        token.setRememberMe(true);
        
		securityService.login(subject,token);
		assertEquals(true, subject.isAuthenticated());		
	}

}
