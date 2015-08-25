package com.ikanow.aleph2.security.service;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.Subject;

import com.google.inject.Inject;
import com.google.inject.Module;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IExtraDependencyLoader;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ISecurityService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IServiceContext;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ISubject;
import com.ikanow.aleph2.data_model.interfaces.shared_services.Identity;
import com.ikanow.aleph2.security.module.IkanowV1SecurityModule;

public class IkanowV1SecurityService implements ISecurityService, IExtraDependencyLoader{
	
	
	protected ISubject currentSubject = null;
	private static final Logger logger = LogManager.getLogger(IkanowV1SecurityService.class);
	@Inject
	protected IServiceContext serviceContext;

	
	@Inject
	public IkanowV1SecurityService(IServiceContext serviceContext, SecurityManager securityManager) {
		this.serviceContext = serviceContext;
		SecurityUtils.setSecurityManager(securityManager);
	}


	protected void init(){
		try {


	        // get the currently executing user:
	        Subject currentUser = SecurityUtils.getSubject();
	        this.currentSubject = new SubjectWrapper(currentUser);
	        // Do some stuff with a Session (no need for a web or EJB container!!!)
	        
		} catch (Throwable e) {
			logger.error("Caught exception",e);
		}

	}
	
	@Override
	public Collection<Object> getUnderlyingArtefacts() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <T> Optional<T> getUnderlyingPlatformDriver(Class<T> driver_class, Optional<String> driver_options) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean hasPermission(Identity identity, Class<?> resourceClass, String resourceIdentifier, String operation) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean hasPermission(Identity identity, String resourceName, String resourceIdentifier, String operation) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public Identity getIdentity(Map<String, Object> token) throws Exception {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void grantPermission(Identity identity, Class<?> resourceClass, String resourceIdentifier, String operation) {
		// TODO Auto-generated method stub

	}

	@Override
	public void grantPermission(Identity identity, String resourceName, String resourceIdentifier, String operation) {
		// TODO Auto-generated method stub

	}

	@Override
	public void revokePermission(Identity identity, Class<?> resourceClass, String resourceIdentifier, String operation) {
		// TODO Auto-generated method stub

	}

	@Override
	public void revokePermission(Identity identity, String resourceName, String resourceIdentifier, String operation) {
		// TODO Auto-generated method stub

	}

	@Override
	public void clearPermission(Class<?> resourceClass, String resourceIdentifier) {
		// TODO Auto-generated method stub

	}

	@Override
	public void clearPermission(String resourceName, String resourceIdentifier) {
		// TODO Auto-generated method stub

	}

	// ***************** new functions ***********************//
	@Override
	public ISubject getSubject() {
		if(currentSubject == null){
			init();
		}
		return currentSubject;
	}

	@Override
	public void login(ISubject subject, Object token) {
		
		((Subject)getSubject().getSubject()).login((AuthenticationToken)token);
		
	}

	@Override
	public boolean hasRole(ISubject subject, String roleIdentifier) {
		boolean ret = ((Subject)getSubject().getSubject()).hasRole(roleIdentifier);
		return ret;
	}

	
	public static List<Module> getExtraDependencyModules() {
		return Arrays.asList((Module)new IkanowV1SecurityModule());
	}


	@Override
	public void youNeedToImplementTheStaticFunctionCalled_getExtraDependencyModules() {
		// TODO Auto-generated method stub
		
	}


	@Override
	public Object isPermitted(ISubject subject, String permission) {
		boolean ret = ((Subject)getSubject().getSubject()).isPermitted(permission);
		return ret;
	}
	
	
	
}
