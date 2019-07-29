/*
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

package org.springframework.transaction;

/**
 * This is the central interface in Spring's transaction infrastructure.        spring事务的核心接口
 * Applications can use this directly, but it is not primarily meant as API:
 * Typically, applications will work with either TransactionTemplate or        一般来讲，应用可以通过TransactionTemplate或者aop来实现事务
 * declarative transaction demarcation through AOP.
 *
 * <p>For implementors, it is recommended to derive from the provided
 * {@link org.springframework.transaction.support.AbstractPlatformTransactionManager}  对于用户来说，建议实现AbstractPlatformTransactionManager接口
 * class, which pre-implements the defined propagation behavior and takes care         这个类已经实现了传播特性、以及事务同步处理
 * of transaction synchronization handling. Subclasses have to implement
 * template methods for specific states of the underlying transaction,               子类需要实现特定的模板方法
 * for example: begin, suspend, resume, commit.
 *
 * <p>The default implementations of this strategy interface are
 * {@link org.springframework.transaction.jta.JtaTransactionManager} and
 * {@link org.springframework.jdbc.datasource.DataSourceTransactionManager},
 * which can serve as an implementation guide for other transaction strategies.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @since 16.05.2003
 * @see org.springframework.transaction.support.TransactionTemplate
 * @see org.springframework.transaction.interceptor.TransactionInterceptor
 * @see org.springframework.transaction.interceptor.TransactionProxyFactoryBean
 */
public interface PlatformTransactionManager {

	/**
	 * Return a currently active transaction or create a new one, according to           返回当前生效的事务或者创建一个新的，根据给定的传播级别
	 * the specified propagation behavior.
	 * <p>Note that parameters like isolation level or timeout will only be applied      需要注意的是，隔离级别和超时特性只会对新事务生效
	 * to new transactions, and thus be ignored when participating in active ones.       否则，会被忽略
	 * <p>Furthermore, not all transaction definition settings will be supported         此外，也不是所有的事务设置都会被事务管理器支持，一个好的事务管理器实现
	 * by every transaction manager: A proper transaction manager implementation         在遇到不支持的设置时要抛出异常
	 * should throw an exception when unsupported settings are encountered.
	 * <p>An exception to the above rule is the read-only flag, which should be
	 * ignored if no explicit read-only mode is supported. Essentially, the
	 * read-only flag is just a hint for potential optimization.
	 * @param definition TransactionDefinition instance (can be {@code null} for defaults),
	 * describing propagation behavior, isolation level, timeout etc.
	 * @return transaction status object representing the new or current transaction
	 * @throws TransactionException in case of lookup, creation, or system errors
	 * @throws IllegalTransactionStateException if the given transaction definition
	 * cannot be executed (for example, if a currently active transaction is in
	 * conflict with the specified propagation behavior)
	 * @see TransactionDefinition#getPropagationBehavior
	 * @see TransactionDefinition#getIsolationLevel
	 * @see TransactionDefinition#getTimeout
	 * @see TransactionDefinition#isReadOnly
	 */
	TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException;

	/**
	 * Commit the given transaction, with regard to its status. If the transaction   根据status的值来提交指定的事务
	 * has been marked rollback-only programmatically, perform a rollback.           如果事务被指定为read-only，就回滚
	 * <p>If the transaction wasn't a new one, omit the commit for proper            如果这个事务不是一个新的，
	 * participation in the surrounding transaction. If a previous transaction       为了创建一个新的事务，如果之前的事务已经被suspend
	 * has been suspended to be able to create a new one, resume the previous        提交这个事务之后，恢复之前的那个事务
	 * transaction after committing the new one.
	 * <p>Note that when the commit call completes, no matter if normally or         当commit调用完成之后，不管是正常结束还是抛了一个异常
	 * throwing an exception, the transaction must be fully completed and            事务必须完全完成和清除
	 * cleaned up. No rollback call should be expected in such a case.               这种情况下不会再有rollback的调用
	 * <p>If this method throws an exception other than a TransactionException,      如果这个方法抛出了一个异常，且不是TransactionException
	 * then some before-commit error caused the commit attempt to fail. For          那么，一些error会导致commit失败
	 * example, an O/R Mapping tool might have tried to flush changes to the
	 * database right before commit, with the resulting DataAccessException
	 * causing the transaction to fail. The original exception will be
	 * propagated to the caller of this commit method in such a case.
	 * @param status object returned by the {@code getTransaction} method
	 * @throws UnexpectedRollbackException in case of an unexpected rollback
	 * that the transaction coordinator initiated
	 * @throws HeuristicCompletionException in case of a transaction failure
	 * caused by a heuristic decision on the side of the transaction coordinator
	 * @throws TransactionSystemException in case of commit or system errors
	 * (typically caused by fundamental resource failures)
	 * @throws IllegalTransactionStateException if the given transaction
	 * is already completed (that is, committed or rolled back)
	 * @see TransactionStatus#setRollbackOnly
	 */
	void commit(TransactionStatus status) throws TransactionException;

	/**
	 * Perform a rollback of the given transaction.
	 * <p>If the transaction wasn't a new one, just set it rollback-only for proper
	 * participation in the surrounding transaction. If a previous transaction
	 * has been suspended to be able to create a new one, resume the previous
	 * transaction after rolling back the new one.
	 * <p><b>Do not call rollback on a transaction if commit threw an exception.</b>
	 * The transaction will already have been completed and cleaned up when commit
	 * returns, even in case of a commit exception. Consequently, a rollback call
	 * after commit failure will lead to an IllegalTransactionStateException.
	 * @param status object returned by the {@code getTransaction} method
	 * @throws TransactionSystemException in case of rollback or system errors
	 * (typically caused by fundamental resource failures)
	 * @throws IllegalTransactionStateException if the given transaction
	 * is already completed (that is, committed or rolled back)
	 */
	void rollback(TransactionStatus status) throws TransactionException;

}
