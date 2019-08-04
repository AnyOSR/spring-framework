/*
 * Copyright 2002-2013 the original author or authors.
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

package org.springframework.jdbc.datasource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

/**
 * Proxy for a target JDBC {@link javax.sql.DataSource}, adding awareness of            增加spring管理事务  感知
 * Spring-managed transactions. Similar to a transactional JNDI DataSource
 * as provided by a J2EE server.
 *
 * <p>Data access code that should remain unaware of Spring's data access support       (没有spring数据获取支持的)数据获取代码能很好的和这个代理合作，来无缝地加入到spring管理的事务中
 * can work with this proxy to seamlessly participate in Spring-managed transactions.
 * Note that the transaction manager, for example {@link DataSourceTransactionManager},  需要注意的是，一些事务管理器
 * still needs to work with the underlying DataSource, <i>not</i> with this proxy.       仍然需要这个潜在的DataSourceProxy
 *
 * <p><b>Make sure that TransactionAwareDataSourceProxy is the outermost DataSource         需要确保TransactionAwareDataSourceProxy是
 * of a chain of DataSource proxies/adapters.</b> TransactionAwareDataSourceProxy           DataSource代理链/适配器中最外面的一个 DataSource
 * can delegate either directly to the target connection pool or to some                    TransactionAwareDataSourceProxy可以直接代理连接池，也可以
 * intermediary proxy/adapter like {@link LazyConnectionDataSourceProxy} or                 代理到内部的代理类或者适配类
 * {@link UserCredentialsDataSourceAdapter}.
 *
 * <p>Delegates to {@link DataSourceUtils} for automatically participating in                  代理到DataSourceUtils来自动的 参与到线程绑定的事务
 * thread-bound transactions, for example managed by {@link DataSourceTransactionManager}.
 * {@code getConnection} calls and {@code close} calls on returned Connections
 * will behave properly within a transaction, i.e. always operate on the transactional
 * Connection. If not within a transaction, normal DataSource behavior applies.
 *
 * <p>This proxy allows data access code to work with the plain JDBC API and still          这个代理类允许数据获取代码直接使用JDBC API且仍然参与到spring管理的事务中
 * participate in Spring-managed transactions, similar to JDBC code in a J2EE/JTA
 * environment. However, if possible, use Spring's DataSourceUtils, JdbcTemplate or
 * JDBC operation objects to get transaction participation even without a proxy for
 * the target DataSource, avoiding the need to define such a proxy in the first place.
 *
 * <p>As a further effect, using a transaction-aware DataSource will apply remaining
 * transaction timeouts to all created JDBC (Prepared/Callable)Statement. This means
 * that all operations performed through standard JDBC will automatically participate
 * in Spring-managed transaction timeouts.
 *
 * <p><b>NOTE:</b> This DataSource proxy needs to return wrapped Connections            这个DataSource代理 需要返回一个封装的Connections(实现了ConnectionProxy接口)
 * (which implement the {@link ConnectionProxy} interface) in order to handle           来恰当的处理close调用
 * close calls properly. Therefore, the returned Connections cannot be cast             因此，返回的Connections不能被转换为原生的JDBC连接类型
 * to a native JDBC Connection type such as OracleConnection or to a connection
 * pool implementation type. Use a corresponding
 * {@link org.springframework.jdbc.support.nativejdbc.NativeJdbcExtractor}               使用NativeJdbcExtractor或者Connection.unwrap来检索原生JDBC连接
 * or JDBC 4's {@link Connection#unwrap} to retrieve the native JDBC Connection.
 *
 * @author Juergen Hoeller
 * @since 1.1
 * @see javax.sql.DataSource#getConnection()
 * @see java.sql.Connection#close()
 * @see DataSourceUtils#doGetConnection
 * @see DataSourceUtils#applyTransactionTimeout
 * @see DataSourceUtils#doReleaseConnection
 */
public class TransactionAwareDataSourceProxy extends DelegatingDataSource {

	private boolean reobtainTransactionalConnections = false;


	/**
	 * Create a new TransactionAwareDataSourceProxy.
	 * @see #setTargetDataSource
	 */
	public TransactionAwareDataSourceProxy() {
	}

	/**
	 * Create a new TransactionAwareDataSourceProxy.
	 * @param targetDataSource the target DataSource
	 */
	public TransactionAwareDataSourceProxy(DataSource targetDataSource) {
		super(targetDataSource);
	}

	/**
	 * Specify whether to reobtain the target Connection for each operation       在一个事务中，每次操作是否重新获取连接
	 * performed within a transaction.
	 * <p>The default is "false". Specify "true" to reobtain transactional          事务连接？transactional Connections？
	 * Connections for every call on the Connection proxy; this is advisable
	 * on JBoss if you hold on to a Connection handle across transaction boundaries.
	 * <p>The effect of this setting is similar to the
	 * "hibernate.connection.release_mode" value "after_statement".
	 */
	public void setReobtainTransactionalConnections(boolean reobtainTransactionalConnections) {
		this.reobtainTransactionalConnections = reobtainTransactionalConnections;
	}


	/**
	 * Delegates to DataSourceUtils for automatically participating in Spring-managed
	 * transactions. Throws the original SQLException, if any.
	 * <p>The returned Connection handle implements the ConnectionProxy interface,
	 * allowing to retrieve the underlying target Connection.
	 * @return a transactional Connection if any, a new one else
	 * @see DataSourceUtils#doGetConnection
	 * @see ConnectionProxy#getTargetConnection
	 */
	@Override
	public Connection getConnection() throws SQLException {
		DataSource ds = getTargetDataSource();
		Assert.state(ds != null, "'targetDataSource' is required");
		return getTransactionAwareConnectionProxy(ds);
	}

	/**
	 * Wraps the given Connection with a proxy that delegates every method call to it
	 * but delegates {@code close()} calls to DataSourceUtils.
	 * @param targetDataSource DataSource that the Connection came from
	 * @return the wrapped Connection
	 * @see java.sql.Connection#close()
	 * @see DataSourceUtils#doReleaseConnection
	 */
	protected Connection getTransactionAwareConnectionProxy(DataSource targetDataSource) {
		return (Connection) Proxy.newProxyInstance(
				ConnectionProxy.class.getClassLoader(),
				new Class<?>[] {ConnectionProxy.class},
				new TransactionAwareInvocationHandler(targetDataSource));
	}

	/**
	 * Determine whether to obtain a fixed target Connection for the proxy
	 * or to reobtain the target Connection for each operation.
	 * <p>The default implementation returns {@code true} for all
	 * standard cases. This can be overridden through the
	 * {@link #setReobtainTransactionalConnections "reobtainTransactionalConnections"}
	 * flag, which enforces a non-fixed target Connection within an active transaction.
	 * Note that non-transactional access will always use a fixed Connection.
	 * @param targetDataSource the target DataSource
	 */
	protected boolean shouldObtainFixedConnection(DataSource targetDataSource) {
		return (!TransactionSynchronizationManager.isSynchronizationActive() || !this.reobtainTransactionalConnections);
	}


	/**
	 * Invocation handler that delegates close calls on JDBC Connections
	 * to DataSourceUtils for being aware of thread-bound transactions.
	 */
	private class TransactionAwareInvocationHandler implements InvocationHandler {

		private final DataSource targetDataSource;

		private Connection target;

		private boolean closed = false;

		public TransactionAwareInvocationHandler(DataSource targetDataSource) {
			this.targetDataSource = targetDataSource;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			// Invocation on ConnectionProxy interface coming in...

			if (method.getName().equals("equals")) {
				// Only considered as equal when proxies are identical.
				return (proxy == args[0]);
			}
			else if (method.getName().equals("hashCode")) {
				// Use hashCode of Connection proxy.
				return System.identityHashCode(proxy);
			}
			else if (method.getName().equals("toString")) {
				// Allow for differentiating between the proxy and the raw Connection.
				StringBuilder sb = new StringBuilder("Transaction-aware proxy for target Connection ");
				if (this.target != null) {
					sb.append("[").append(this.target.toString()).append("]");
				}
				else {
					sb.append(" from DataSource [").append(this.targetDataSource).append("]");
				}
				return sb.toString();
			}
			else if (method.getName().equals("unwrap")) {
				if (((Class<?>) args[0]).isInstance(proxy)) {    // proxy是不是第一个入参子类的实例
					return proxy;
				}
			}
			else if (method.getName().equals("isWrapperFor")) {
				if (((Class<?>) args[0]).isInstance(proxy)) {
					return true;
				}
			}
			else if (method.getName().equals("close")) {
				// Handle close method: only close if not within a transaction.
				DataSourceUtils.doReleaseConnection(this.target, this.targetDataSource);
				this.closed = true;
				return null;
			}
			else if (method.getName().equals("isClosed")) {
				return this.closed;
			}

			if (this.target == null) {    // 如果target为null，可能需要创建target
				if (this.closed) {          // 如果已经关闭
					throw new SQLException("Connection handle already closed");
				}
				if (shouldObtainFixedConnection(this.targetDataSource)) {    // 如果是获取固定的连接，则给target赋值
					this.target = DataSourceUtils.doGetConnection(this.targetDataSource);
				}
			}
			Connection actualTarget = this.target;
			// 每次获取固定连接时：首次获取时，会给target赋值，再次获取时，actualTarget不为null,不会再次调用doGetConnection
			if (actualTarget == null) {
				actualTarget = DataSourceUtils.doGetConnection(this.targetDataSource);
			}

			// 如果是getTargetConnection调用
			if (method.getName().equals("getTargetConnection")) {
				// Handle getTargetConnection method: return underlying Connection.
				return actualTarget;
			}

			// Invoke method on target Connection.
            // 其余调用
			try {
				Object retVal = method.invoke(actualTarget, args);

				// If return value is a Statement, apply transaction timeout.
				// Applies to createStatement, prepareStatement, prepareCall.
				if (retVal instanceof Statement) {
					DataSourceUtils.applyTransactionTimeout((Statement) retVal, this.targetDataSource);
				}

				return retVal;
			}
			catch (InvocationTargetException ex) {
				throw ex.getTargetException();
			}
			finally {
			    // 如果不是获取固定的连接，每次用完之后释放连接
				if (actualTarget != this.target) {
					DataSourceUtils.doReleaseConnection(actualTarget, this.targetDataSource);
				}
			}
		}
	}

}
