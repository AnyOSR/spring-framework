/*
 * Copyright 2002-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCreationNotAllowedException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.core.SimpleAliasRegistry;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Generic registry for shared bean instances, implementing the
 * {@link org.springframework.beans.factory.config.SingletonBeanRegistry}.
 * Allows for registering singleton instances that should be shared
 * for all callers of the registry, to be obtained via bean name.
 *
 * <p>Also supports registration of
 * {@link org.springframework.beans.factory.DisposableBean} instances,
 * (which might or might not correspond to registered singletons),
 * to be destroyed on shutdown of the registry. Dependencies between
 * beans can be registered to enforce an appropriate shutdown order.
 *
 * <p>This class mainly serves as base class for
 * {@link org.springframework.beans.factory.BeanFactory} implementations,
 * factoring out the common management of singleton bean instances. Note that
 * the {@link org.springframework.beans.factory.config.ConfigurableBeanFactory}
 * interface extends the {@link SingletonBeanRegistry} interface.
 *
 * <p>Note that this class assumes neither a bean definition concept
 * nor a specific creation process for bean instances, in contrast to
 * {@link AbstractBeanFactory} and {@link DefaultListableBeanFactory}
 * (which inherit from it). Can alternatively also be used as a nested
 * helper to delegate to.
 *
 * @author Juergen Hoeller
 * @since 2.0
 * @see #registerSingleton
 * @see #registerDisposableBean
 * @see org.springframework.beans.factory.DisposableBean
 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory
 */
public class DefaultSingletonBeanRegistry extends SimpleAliasRegistry implements SingletonBeanRegistry {

	/**
	 * Internal marker for a null singleton object:
	 * used as marker value for concurrent Maps (which don't support null values).
	 */
	protected static final Object NULL_OBJECT = new Object();


	/** Logger available to subclasses */
	protected final Log logger = LogFactory.getLog(getClass());

	/** Cache of singleton objects: bean name --> bean instance */
	private final Map<String, Object> singletonObjects = new ConcurrentHashMap<String, Object>(256);

	/** Cache of singleton factories: bean name --> ObjectFactory */
	private final Map<String, ObjectFactory<?>> singletonFactories = new HashMap<String, ObjectFactory<?>>(16);

	/** Cache of early singleton objects: bean name --> bean instance */
	private final Map<String, Object> earlySingletonObjects = new HashMap<String, Object>(16);

	/** Set of registered singletons, containing the bean names in registration order */
	private final Set<String> registeredSingletons = new LinkedHashSet<String>(256);

	// 对于objectFactory产生beanName的过程中，必须处于singletonsCurrentlyInCreation或者inCreationCheckExclusions中
	/** Names of beans that are currently in creation */
	// 正在创建中的bean
	private final Set<String> singletonsCurrentlyInCreation = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>(16));

	/** Names of beans currently excluded from in creation checks */
	// 创建过程中 可以忽略的beanName检查
	private final Set<String> inCreationCheckExclusions = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>(16));

	/** List of suppressed Exceptions, available for associating related causes */
	private Set<Exception> suppressedExceptions;

	/** Flag that indicates whether we're currently within destroySingletons */
	private boolean singletonsCurrentlyInDestruction = false;

	/** Disposable bean instances: bean name --> disposable instance */
	private final Map<String, Object> disposableBeans = new LinkedHashMap<String, Object>();

	/** Map between containing bean names: bean name --> Set of bean names that the bean contains */
	// bean 这个bean包含的beanName集合
	private final Map<String, Set<String>> containedBeanMap = new ConcurrentHashMap<String, Set<String>>(16);

	/** Map between dependent bean names: bean name --> Set of dependent bean names */
	// beanName 依赖于这个beanName的bean集合
	// 内部bean --> 依赖该bean的所有外部bean (外部bean依赖内部bean)
	private final Map<String, Set<String>> dependentBeanMap = new ConcurrentHashMap<String, Set<String>>(64);

	/** Map between depending bean names: bean name --> Set of bean names for the bean's dependencies */
	// beanName -- 该bean依赖的bean
	// 外部bean --> 该bean依赖的所有内部bean集合
	private final Map<String, Set<String>> dependenciesForBeanMap = new ConcurrentHashMap<String, Set<String>>(64);


	@Override
	public void registerSingleton(String beanName, Object singletonObject) throws IllegalStateException {
		Assert.notNull(beanName, "'beanName' must not be null");
		synchronized (this.singletonObjects) {
			Object oldObject = this.singletonObjects.get(beanName);
			if (oldObject != null) {
				throw new IllegalStateException("Could not register object [" + singletonObject +
						"] under bean name '" + beanName + "': there is already object [" + oldObject + "] bound");
			}
			addSingleton(beanName, singletonObject);
		}
	}

	/**
	 * Add the given singleton object to the singleton cache of this factory.
	 * <p>To be called for eager registration of singletons.
	 * @param beanName the name of the bean
	 * @param singletonObject the singleton object
	 */
	//  终态
	//  singletonObjects
	//	registeredSingletons
	protected void addSingleton(String beanName, Object singletonObject) {
		synchronized (this.singletonObjects) {
			this.singletonObjects.put(beanName, (singletonObject != null ? singletonObject : NULL_OBJECT));
			this.registeredSingletons.add(beanName);

			this.singletonFactories.remove(beanName);
			this.earlySingletonObjects.remove(beanName);
		}
	}

	/**
	 * Add the given singleton factory for building the specified singleton
	 * if necessary.
	 * <p>To be called for eager registration of singletons, e.g. to be able to
	 * resolve circular references.
	 * @param beanName the name of the bean
	 * @param singletonFactory the factory for the singleton object
	 */
	// singletonObjects和singletonObjects应该是互斥的
	// 最终解释权在singletonObjects
	protected void addSingletonFactory(String beanName, ObjectFactory<?> singletonFactory) {
		Assert.notNull(singletonFactory, "Singleton factory must not be null");
		synchronized (this.singletonObjects) {
			if (!this.singletonObjects.containsKey(beanName)) {    // 如果不是已经创建完成
				this.singletonFactories.put(beanName, singletonFactory);
				this.registeredSingletons.add(beanName);

				this.earlySingletonObjects.remove(beanName);
			}
		}
	}

	@Override
	public Object getSingleton(String beanName) {
		return getSingleton(beanName, true);
	}

	/**
	 * Return the (raw) singleton object registered under the given name.
	 * <p>Checks already instantiated singletons and also allows for an early
	 * reference to a currently created singleton (resolving a circular reference).
	 * @param beanName the name of the bean to look for
	 * @param allowEarlyReference whether early references should be created or not
	 * @return the registered singleton object, or {@code null} if none found
	 */
	// earlySingletonObjects和singletonFactories互斥
	protected Object getSingleton(String beanName, boolean allowEarlyReference) {
		Object singletonObject = this.singletonObjects.get(beanName);
		if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {   // 如果没有创建完 且正在创建  则objectFactory  循环引用问题
			synchronized (this.singletonObjects) {
				singletonObject = this.earlySingletonObjects.get(beanName);
				// 如果singletonObject为null，且允许early，则获取对应的ObjectFactory
				if (singletonObject == null && allowEarlyReference) {
					ObjectFactory<?> singletonFactory = this.singletonFactories.get(beanName);
					if (singletonFactory != null) {
						singletonObject = singletonFactory.getObject();
						this.earlySingletonObjects.put(beanName, singletonObject);   // 将singletonFactory创建的bean放入earlySingletonObjects
						this.singletonFactories.remove(beanName);                    // 删除掉对应的singletonFactory
					}
				}
			}
		}
		return (singletonObject != NULL_OBJECT ? singletonObject : null);
	}

	/**
	 * Return the (raw) singleton object registered under the given name,
	 * creating and registering a new one if none registered yet.
	 * @param beanName the name of the bean
	 * @param singletonFactory the ObjectFactory to lazily create the singleton
	 * with, if necessary
	 * @return the registered singleton object
	 */
	// 首先从singletonObjects(已成功创建)中获取
	// 否则利用singletonFactory创建，并加入到singletonObjects中
	// singletonFactory和
	public Object getSingleton(String beanName, ObjectFactory<?> singletonFactory) {
		Assert.notNull(beanName, "'beanName' must not be null");
		synchronized (this.singletonObjects) {
			Object singletonObject = this.singletonObjects.get(beanName);
			if (singletonObject == null) {
				if (this.singletonsCurrentlyInDestruction) {
					throw new BeanCreationNotAllowedException(beanName,
							"Singleton bean creation not allowed while singletons of this factory are in destruction " +
							"(Do not request a bean from a BeanFactory in a destroy method implementation!)");
				}
				if (logger.isDebugEnabled()) {
					logger.debug("Creating shared instance of singleton bean '" + beanName + "'");
				}
				// 将beanName加入到singletonsCurrentlyInCreation中 或者不需要检查
				// 只会在关系到singletonFactory时 会联系到singletonsCurrentlyInCreation
				beforeSingletonCreation(beanName);
				boolean newSingleton = false;
				boolean recordSuppressedExceptions = (this.suppressedExceptions == null);
				if (recordSuppressedExceptions) {
					this.suppressedExceptions = new LinkedHashSet<Exception>();
				}
				try {
					singletonObject = singletonFactory.getObject();
					newSingleton = true;
				}
				catch (IllegalStateException ex) {
					// Has the singleton object implicitly appeared in the meantime ->
					// if yes, proceed with it since the exception indicates that state.
					singletonObject = this.singletonObjects.get(beanName);
					if (singletonObject == null) {
						throw ex;
					}
				}
				catch (BeanCreationException ex) {
					if (recordSuppressedExceptions) {
						for (Exception suppressedException : this.suppressedExceptions) {
							ex.addRelatedCause(suppressedException);
						}
					}
					throw ex;
				}
				finally {
					if (recordSuppressedExceptions) {
						this.suppressedExceptions = null;
					}
					// 当前beanName不需要检查 或者从singletonsCurrentlyInCreation中成功remove
					afterSingletonCreation(beanName);
				}
				// 如果成功创建
				if (newSingleton) {
					addSingleton(beanName, singletonObject);
				}
			}
			return (singletonObject != NULL_OBJECT ? singletonObject : null);
		}
	}

	/**
	 * Register an Exception that happened to get suppressed during the creation of a
	 * singleton bean instance, e.g. a temporary circular reference resolution problem.
	 * @param ex the Exception to register
	 */
	// 将ex加入到suppressedExceptions中
	protected void onSuppressedException(Exception ex) {
		synchronized (this.singletonObjects) {
			if (this.suppressedExceptions != null) {
				this.suppressedExceptions.add(ex);
			}
		}
	}

	/**
	 * Remove the bean with the given name from the singleton cache of this factory,
	 * to be able to clean up eager registration of a singleton if creation failed.
	 * @param beanName the name of the bean
	 * @see #getSingletonMutex()
	 */
	protected void removeSingleton(String beanName) {
		synchronized (this.singletonObjects) {
			this.singletonObjects.remove(beanName);
			this.singletonFactories.remove(beanName);
			this.earlySingletonObjects.remove(beanName);
			this.registeredSingletons.remove(beanName);
		}
	}

	@Override
	public boolean containsSingleton(String beanName) {
		return this.singletonObjects.containsKey(beanName);
	}

	@Override
	public String[] getSingletonNames() {
		synchronized (this.singletonObjects) {
			return StringUtils.toStringArray(this.registeredSingletons);
		}
	}

	@Override
	public int getSingletonCount() {
		synchronized (this.singletonObjects) {
			return this.registeredSingletons.size();
		}
	}

	// 将beanName设置为在创建中
	// 1.如果没有真的在创建，则加入到inCreationCheckExclusions中，创建时不会检查
	// 2.如果真的在创建，则从inCreationCheckExclusions中移除，创建时会检查singletonsCurrentlyInCreation中是否存在
	public void setCurrentlyInCreation(String beanName, boolean inCreation) {
		Assert.notNull(beanName, "Bean name must not be null");
		if (!inCreation) {
			this.inCreationCheckExclusions.add(beanName);
		}
		else {
			this.inCreationCheckExclusions.remove(beanName);
		}
	}

	// beanName是否真的在创建中
	public boolean isCurrentlyInCreation(String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		return (!this.inCreationCheckExclusions.contains(beanName) && isActuallyInCreation(beanName));
	}

	protected boolean isActuallyInCreation(String beanName) {
		return isSingletonCurrentlyInCreation(beanName);
	}

	/**
	 * Return whether the specified singleton bean is currently in creation
	 * (within the entire factory).
	 * @param beanName the name of the bean
	 */
	public boolean isSingletonCurrentlyInCreation(String beanName) {
		return this.singletonsCurrentlyInCreation.contains(beanName);
	}

	/**
	 * Callback before singleton creation.
	 * <p>The default implementation register the singleton as currently in creation.
	 * @param beanName the name of the singleton about to be created
	 * @see #isSingletonCurrentlyInCreation
	 */
	// 加入到singletonsCurrentlyInCreation中成功
	// 或者beanName在inCreationCheckExclusions中，不需要检查
	protected void beforeSingletonCreation(String beanName) {
		if (!this.inCreationCheckExclusions.contains(beanName) && !this.singletonsCurrentlyInCreation.add(beanName)) {
			throw new BeanCurrentlyInCreationException(beanName);
		}
	}

	/**
	 * Callback after singleton creation.
	 * <p>The default implementation marks the singleton as not in creation anymore.
	 * @param beanName the name of the singleton that has been created
	 * @see #isSingletonCurrentlyInCreation
	 */
	// 不需要检查 或者remove成功
	protected void afterSingletonCreation(String beanName) {
		if (!this.inCreationCheckExclusions.contains(beanName) && !this.singletonsCurrentlyInCreation.remove(beanName)) {
			throw new IllegalStateException("Singleton '" + beanName + "' isn't currently in creation");
		}
	}


	/**
	 * Add the given bean to the list of disposable beans in this registry.
	 * <p>Disposable beans usually correspond to registered singletons,
	 * matching the bean name but potentially being a different instance
	 * (for example, a DisposableBean adapter for a singleton that does not
	 * naturally implement Spring's DisposableBean interface).
	 * @param beanName the name of the bean
	 * @param bean the bean instance
	 */
	public void registerDisposableBean(String beanName, DisposableBean bean) {
		synchronized (this.disposableBeans) {
			this.disposableBeans.put(beanName, bean);
		}
	}

	/**
	 * Register a containment relationship between two beans,
	 * e.g. between an inner bean and its containing outer bean.
	 * <p>Also registers the containing bean as dependent on the contained bean
	 * in terms of destruction order.
	 * @param containedBeanName the name of the contained (inner) bean            内部bean
	 * @param containingBeanName the name of the containing (outer) bean          外部bean
	 * @see #registerDependentBean
	 */
	public void registerContainedBean(String containedBeanName, String containingBeanName) {
		// A quick check for an existing entry upfront, avoiding synchronization...
		Set<String> containedBeans = this.containedBeanMap.get(containingBeanName);
		if (containedBeans != null && containedBeans.contains(containedBeanName)) {
			return;
		}

		// No entry yet -> fully synchronized manipulation of the containedBeans Set
		synchronized (this.containedBeanMap) {
			containedBeans = this.containedBeanMap.get(containingBeanName);
			if (containedBeans == null) {
				containedBeans = new LinkedHashSet<String>(8);
				this.containedBeanMap.put(containingBeanName, containedBeans);
			}
			containedBeans.add(containedBeanName);
		}
		// containedBeanName被包含bean containingBeanName外部bean
		// containingBeanName依赖于containedBeanName
		registerDependentBean(containedBeanName, containingBeanName);
	}

	/**
	 * Register a dependent bean for the given bean,
	 * to be destroyed before the given bean is destroyed.
	 * @param beanName the name of the bean                       内部bean
	 * @param dependentBeanName the name of the dependent bean    外部bean
	 */
	// dependentBeanName依赖于beanName
	public void registerDependentBean(String beanName, String dependentBeanName) {
		// A quick check for an existing entry upfront, avoiding synchronization...
		String canonicalName = canonicalName(beanName);
		Set<String> dependentBeans = this.dependentBeanMap.get(canonicalName);
		if (dependentBeans != null && dependentBeans.contains(dependentBeanName)) {
			return;
		}

		// No entry yet -> fully synchronized manipulation of the dependentBeans Set
		synchronized (this.dependentBeanMap) {
			dependentBeans = this.dependentBeanMap.get(canonicalName);
			if (dependentBeans == null) {
				dependentBeans = new LinkedHashSet<String>(8);
				this.dependentBeanMap.put(canonicalName, dependentBeans);
			}
			dependentBeans.add(dependentBeanName);
		}
		synchronized (this.dependenciesForBeanMap) {
			Set<String> dependenciesForBean = this.dependenciesForBeanMap.get(dependentBeanName);
			if (dependenciesForBean == null) {
				dependenciesForBean = new LinkedHashSet<String>(8);
				this.dependenciesForBeanMap.put(dependentBeanName, dependenciesForBean);
			}
			dependenciesForBean.add(canonicalName);
		}
	}

	/**
	 * Determine whether the specified dependent bean has been registered as
	 * dependent on the given bean or on any of its transitive dependencies.
	 * @param beanName the name of the bean to check
	 * @param dependentBeanName the name of the dependent bean
	 * @since 4.0
	 */
	protected boolean isDependent(String beanName, String dependentBeanName) {
		return isDependent(beanName, dependentBeanName, null);
	}

	// 检查dependentBeanName是否依赖于beanName
	private boolean isDependent(String beanName, String dependentBeanName, Set<String> alreadySeen) {
		if (alreadySeen != null && alreadySeen.contains(beanName)) { // 循环依赖到头了 还没有返回true 可以终止了
			return false;
		}
		String canonicalName = canonicalName(beanName);
		Set<String> dependentBeans = this.dependentBeanMap.get(canonicalName);  // 获取依赖于beanName的bean集合
		if (dependentBeans == null) {
			return false;
		}
		if (dependentBeans.contains(dependentBeanName)) {  // 如果直接依赖，直接返回true
			return true;
		}

		// 可能会间接依赖
		// 遍历所有依赖于beanName的bean集合
		for (String transitiveDependency : dependentBeans) {
			if (alreadySeen == null) {
				alreadySeen = new HashSet<String>();
			}
            // 由于transitiveDependency也是依赖于beanName
            // 所以alreadySeen里面存放的是到目前为止，所有依赖于beanName的bean，并且还拥有间接依赖的
			alreadySeen.add(beanName);
			if (isDependent(transitiveDependency, dependentBeanName, alreadySeen)) {  // 是否间接依赖
				return true;
			}
		}
		return false;
	}

	/**
	 * Determine whether a dependent bean has been registered for the given name.
	 * @param beanName the name of the bean to check
	 */
	protected boolean hasDependentBean(String beanName) {
		return this.dependentBeanMap.containsKey(beanName);
	}

	/**
	 * Return the names of all beans which depend on the specified bean, if any.
	 * @param beanName the name of the bean
	 * @return the array of dependent bean names, or an empty array if none
	 */
	public String[] getDependentBeans(String beanName) {
		Set<String> dependentBeans = this.dependentBeanMap.get(beanName);
		if (dependentBeans == null) {
			return new String[0];
		}
		return StringUtils.toStringArray(dependentBeans);
	}

	/**
	 * Return the names of all beans that the specified bean depends on, if any.   获取beanName所有的依赖bean
	 * @param beanName the name of the bean
	 * @return the array of names of beans which the bean depends on,
	 * or an empty array if none
	 */
	public String[] getDependenciesForBean(String beanName) {
		Set<String> dependenciesForBean = this.dependenciesForBeanMap.get(beanName);
		if (dependenciesForBean == null) {
			return new String[0];
		}
		return dependenciesForBean.toArray(new String[dependenciesForBean.size()]);
	}

	public void destroySingletons() {
		if (logger.isDebugEnabled()) {
			logger.debug("Destroying singletons in " + this);
		}
		synchronized (this.singletonObjects) {
			this.singletonsCurrentlyInDestruction = true;  // 当前正在destruct中
		}

		String[] disposableBeanNames;
		synchronized (this.disposableBeans) {   // destroy disposableBeans
			disposableBeanNames = StringUtils.toStringArray(this.disposableBeans.keySet());
		}
		for (int i = disposableBeanNames.length - 1; i >= 0; i--) {
			destroySingleton(disposableBeanNames[i]);
		}

		//剩下的直接清除
		this.containedBeanMap.clear();
		this.dependentBeanMap.clear();
		this.dependenciesForBeanMap.clear();

		synchronized (this.singletonObjects) {
			this.singletonObjects.clear();
			this.singletonFactories.clear();
			this.earlySingletonObjects.clear();
			this.registeredSingletons.clear();
			this.singletonsCurrentlyInDestruction = false;
		}
	}

	/**
	 * Destroy the given bean. Delegates to {@code destroyBean}
	 * if a corresponding disposable bean instance is found.
	 * @param beanName the name of the bean
	 * @see #destroyBean
	 */
	public void destroySingleton(String beanName) {
		// Remove a registered singleton of the given name, if any.
		// 从基础数据结构中直接删除
		removeSingleton(beanName);

		// Destroy the corresponding DisposableBean instance.
		DisposableBean disposableBean;
		// 从disposableBeans中移除
		synchronized (this.disposableBeans) {
			disposableBean = (DisposableBean) this.disposableBeans.remove(beanName);
		}
		destroyBean(beanName, disposableBean);
	}

	/**
	 * Destroy the given bean. Must destroy beans that depend on the given
	 * bean before the bean itself. Should not throw any exceptions.
	 * @param beanName the name of the bean
	 * @param bean the bean instance to destroy
	 */
	// destroy bean
	protected void destroyBean(String beanName, DisposableBean bean) {
		// Trigger destruction of dependent beans first...
		Set<String> dependencies = this.dependentBeanMap.remove(beanName);    // 获取依赖于beanName的bean集合
		if (dependencies != null) {
			if (logger.isDebugEnabled()) {
				logger.debug("Retrieved dependent beans for bean '" + beanName + "': " + dependencies);
			}
			for (String dependentBeanName : dependencies) {  // 先destroy掉依赖bean
				destroySingleton(dependentBeanName);
			}
		}

		// Actually destroy the bean now...
		if (bean != null) {
			try {
				bean.destroy();   // destroy self
			}
			catch (Throwable ex) {
				logger.error("Destroy method on bean with name '" + beanName + "' threw an exception", ex);
			}
		}

		// Trigger destruction of contained beans...
		Set<String> containedBeans = this.containedBeanMap.remove(beanName);  // 获取beanName 包含的bean集合
		if (containedBeans != null) {
			for (String containedBeanName : containedBeans) {
				destroySingleton(containedBeanName);    // destroy 这些bean
			}
		}

		// Remove destroyed bean from other beans' dependencies.
		synchronized (this.dependentBeanMap) {
			for (Iterator<Map.Entry<String, Set<String>>> it = this.dependentBeanMap.entrySet().iterator(); it.hasNext();) {
				Map.Entry<String, Set<String>> entry = it.next();
				Set<String> dependenciesToClean = entry.getValue();
				dependenciesToClean.remove(beanName);     // 清除数据结构 没有这个bean了，也就不依赖别的bean了
				if (dependenciesToClean.isEmpty()) {
					it.remove();
				}
			}
		}

		// Remove destroyed bean's prepared dependency information.
		this.dependenciesForBeanMap.remove(beanName);     // 清除多余的数据结构
	}

	/**
	 * Exposes the singleton mutex to subclasses and external collaborators.
	 * <p>Subclasses should synchronize on the given Object if they perform
	 * any sort of extended singleton creation phase. In particular, subclasses
	 * should <i>not</i> have their own mutexes involved in singleton creation,
	 * to avoid the potential for deadlocks in lazy-init situations.
	 */
	public final Object getSingletonMutex() {
		return this.singletonObjects;
	}

}
