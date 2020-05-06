/*<
 * Copyright 2002-2012 the original author or authors.
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

package org.springframework.aop;

/**
 * A {@code TargetSource} is used to obtain the current "target" of                 一个TargetSource 用于获取当前 AOP调用的 target
 * an AOP invocation, which will be invoked via reflection if no around              会通过反射被调用 如果没有around advice被选择
 * advice chooses to end the interceptor chain itself.                               来结束 interceptor chain 本身
 *
 * <p>If a {@code TargetSource} is "static", it will always return                   如果 是static的，永远返回同一个对象
 * the same target, allowing optimizations in the AOP framework. Dynamic
 * target sources can support pooling, hot swapping, etc.
 *
 * <p>Application developers don't usually need to work with
 * {@code TargetSources} directly: this is an AOP framework interface.
 *
 * @author Rod Johnson
 */
public interface TargetSource extends TargetClassAware {

	/**
	 * Return the type of targets returned by this {@link TargetSource}.
	 * <p>Can return {@code null}, although certain usages of a
	 * {@code TargetSource} might just work with a predetermined
	 * target class.
	 * @return the type of targets returned by this {@link TargetSource}
	 */
	@Override
	Class<?> getTargetClass();

	/**
	 * Will all calls to {@link #getTarget()} return the same object?          getTarget是否会返回同一个对象
	 * <p>In that case, there will be no need to invoke                        如果是的话，则没有必要调用releaseTarget
	 * {@link #releaseTarget(Object)}, and the AOP framework can cache         AOP框架会cache getTarget的返回值
	 * the return value of {@link #getTarget()}.
	 * @return {@code true} if the target is immutable
	 * @see #getTarget
	 */
	boolean isStatic();

	/**
	 * Return a target instance. Invoked immediately before the                  返回一个目标实例
	 * AOP framework calls the "target" of an AOP method invocation.             在aop框架 调用目标的 AOP调用 之前被调用
	 * @return the target object, which contains the joinpoint
	 * @throws Exception if the target object can't be resolved
	 */
	Object getTarget() throws Exception;

	/**
	 * Release the given target object obtained from the
	 * {@link #getTarget()} method.
	 * @param target object obtained from a call to {@link #getTarget()}
	 * @throws Exception if the object can't be released
	 */
	void releaseTarget(Object target) throws Exception;

}
