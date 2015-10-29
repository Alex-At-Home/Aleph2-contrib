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
package com.ikanow.aleph2.security.module;

import org.apache.shiro.config.Ini;
import org.apache.shiro.realm.ldap.JndiLdapContextFactory;
import org.apache.shiro.realm.text.IniRealm;

import com.google.inject.Provides;
import com.google.inject.multibindings.Multibinder;
import com.ikanow.aleph2.security.interfaces.IRoleProvider;
import com.ikanow.aleph2.security.service.IkanowV2Realm;

public class IkanowV2SecurityModule extends CoreSecurityModule{
	
	
	public IkanowV2SecurityModule(){
	}
	
	@Override
	protected void bindMisc() {
		// do not just bind the implementation class,e.g. IkanowV1DataModificationChecker. This somehow creates an error about EhCachemanager already created.
		//bind(IModificationChecker.class).to(IkanowV1DataModificationChecker.class).asEagerSingleton();
		//expose(IModificationChecker.class);
	}

	@Override
	protected void bindRealms() {

		IkanowV2Realm realm  =  new IkanowV2Realm();
		JndiLdapContextFactory contextFactory = new JndiLdapContextFactory();
		contextFactory.setUrl("ldap://localhost:10389");
		contextFactory.setAuthenticationMechanism("simple");
		realm.setContextFactory(contextFactory);
		realm.setUserDnTemplate("cn={0},ou=users,ou=aleph2,dc=ikanow,dc=com");
		
		bindRealm().toInstance(realm);		
	}
	

	@Override
    protected void bindRoleProviders(){
		Multibinder<IRoleProvider> uriBinder = Multibinder.newSetBinder(binder(), IRoleProvider.class);
//	    uriBinder.addBinding().to(IkanowV1AdminRoleProvider.class);
//	    uriBinder.addBinding().to(IkanowV1UserGroupRoleProvider.class);
//	    uriBinder.addBinding().to(IkanowV1DataGroupRoleProvider.class);
    }
	
	@Override
	protected void bindCredentialsMatcher() {
 		//bind(CredentialsMatcher.class).to(AccountStatusCredentialsMatcher.class);
	}
	
	@Provides
    Ini loadShiroIni() {
        return Ini.fromResourcePath("classpath:shiro.ini");
    }
}
