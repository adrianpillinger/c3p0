/*
 * Distributed as part of c3p0 v.0.9.0-pre4
 *
 * Copyright (C) 2005 Machinery For Change, Inc.
 *
 * Author: Steve Waldman <swaldman@mchange.com>
 *
 * This library is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License version 2.1, as 
 * published by the Free Software Foundation.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this software; see the file LICENSE.  If not, write to the
 * Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 * Boston, MA 02111-1307, USA.
 */


package com.mchange.v2.resourcepool;

import java.util.*;
import com.mchange.v2.async.*;
import com.mchange.v2.log.*;
import com.mchange.v2.holders.SynchronizedIntHolder;
import com.mchange.v2.util.ResourceClosedException;

class BasicResourcePool implements ResourcePool
{
    private final static MLogger logger = MLog.getLogger( BasicResourcePool.class );

    final static int CULL_FREQUENCY_DIVISOR = 8;

    //MT: unchanged post c'tor
    Manager mgr;
    BasicResourcePoolFactory factory;

    //MT: protected by this' lock
    AsynchronousRunner       taskRunner;
    RunnableQueue            asyncEventQueue;
    Timer                    cullAndIdleRefurbishTimer;
    TimerTask                cullTask;
    TimerTask                idleRefurbishTask;
    HashSet                  acquireWaiters = new HashSet();
    HashSet                  otherWaiters = new HashSet();

    /*  keys are all valid, managed resources, value is a Date */ 
    HashMap  managed  = new HashMap();

    /* all valid, managed resources currently available for checkout */
    LinkedList unused   = new LinkedList();

    /* resources which have been invalidated somehow, but which are */
    /* still checked out and in use.                                */
    HashSet  excluded = new HashSet();

    Set idleCheckResources = new HashSet();

    ResourcePoolEventSupport rpes = new ResourcePoolEventSupport(this);

    boolean force_kill_acquires = false;

    boolean broken = false;

    //DEBUG only!
    Object exampleResource;

    //
    // members below are unchanging
    //

    int start;
    int min;
    int max;
    int inc;

    int num_acq_attempts;
    int acq_attempt_delay;

    long check_idle_resources_delay; //milliseconds
    long max_resource_age;           //milliseconds
    boolean age_is_absolute;

    boolean break_on_acquisition_failure;

    //
    // end unchanging members
    //

    // ---

    //
    // members below are changing but protected 
    // by their own locks
    //
    
    SynchronizedIntHolder pendingAcquiresCounter = new SynchronizedIntHolder();

    //
    // end changing but protected members
    //


    /**
     * @param factory may be null
     */
    public BasicResourcePool(Manager                  mgr, 
			     int                      start,
			     int                      min, 
			     int                      max, 
			     int                      inc,
			     int                      num_acq_attempts,
			     int                      acq_attempt_delay,
			     long                     check_idle_resources_delay,
			     long                     max_resource_age,
			     boolean                  age_is_absolute,
			     boolean                  break_on_acquisition_failure,
			     AsynchronousRunner       taskRunner,
			     RunnableQueue            asyncEventQueue,
			     Timer                    cullAndIdleRefurbishTimer,
			     BasicResourcePoolFactory factory)
	throws ResourcePoolException
    {
	try
	    {
		this.mgr                        = mgr;
		this.start                      = start;
		this.min                        = min;
		this.max                        = max;
		this.inc                        = inc;
		this.num_acq_attempts           = num_acq_attempts;
		this.acq_attempt_delay          = acq_attempt_delay;
		this.check_idle_resources_delay = check_idle_resources_delay;
		this.max_resource_age           = max_resource_age;
		this.age_is_absolute            = age_is_absolute;
		this.factory                    = factory;
		this.taskRunner                 = taskRunner;
		this.asyncEventQueue            = asyncEventQueue;
		this.cullAndIdleRefurbishTimer  = cullAndIdleRefurbishTimer;

		pendingAcquiresCounter.setValue( 0 );

		//start acquiring our initial resources
		ensureStartResources();

		if (max_resource_age > 0)
		    {
			long cull_frequency = max_resource_age / CULL_FREQUENCY_DIVISOR ;
			this.cullTask = new CullTask();
			cullAndIdleRefurbishTimer.schedule( cullTask, max_resource_age, cull_frequency );
		    }
		else
		    age_is_absolute = false; // there's no point keeping track of
		                             // the absolute age of things if we 
		                             // aren't even culling.

		if (check_idle_resources_delay > 0)
		    {
			this.idleRefurbishTask = new CheckIdleResourcesTask();
			cullAndIdleRefurbishTimer.schedule( idleRefurbishTask, 
							    check_idle_resources_delay, 
							    check_idle_resources_delay );
		    }
	    }
	catch (Exception e)
	    { throw ResourcePoolUtils.convertThrowable( e ); }
    }

    public Object checkoutResource() 
	throws ResourcePoolException, InterruptedException
    {
	try { return checkoutResource( 0 ); }
	catch (TimeoutException e)
	    {
		//this should never happen
		//e.printStackTrace();
		if ( logger.isLoggable( MLevel.WARNING ) )
		    logger.log( MLevel.WARNING, "Huh??? TimeoutException with no timeout set!!!", e);

		throw new ResourcePoolException("Huh??? TimeoutException with no timeout set!!!", e);
	    }
    }

    /*
     * This function recursively calls itself... under nonpathological
     * situations, it shouldn't be a problem, but if resources can never
     * successfully check out for some reason, we might blow the stack...
     *
     * by the semantics of wait(), a timeout of zero means forever.
     */
    public synchronized Object checkoutResource( long timeout )
	throws TimeoutException, ResourcePoolException, InterruptedException
    {
	try
	    {
		ensureNotBroken();

		int available = unused.size();
		if (available == 0)
		    {
			int msz = managed.size();
			if (msz < max) postAcquireMore();
			awaitAcquire(timeout); //throws timeout exception
		    }

 		Object  resc = unused.get(0);
 		unused.remove(0);

		// this is a hack -- but "doing it right" adds a lot of complexity, and collisions between
		// an idle check and a checkout should be relatively rare. anyway, it should work just fine.
		if ( idleCheckResources.contains( resc ) )
		    {
			if (Debug.DEBUG && logger.isLoggable( MLevel.FINER))
			    logger.log( MLevel.FINER, 
					"Resource we want to check out is in idleCheck! (waiting until idle-check completes.) [" + this + "]");
			//System.err.println("c3p0-JENNIFER: INFO: Resource we want to check out is in idleCheck! (waiting until idle-check completes.)"  + " [" + this + "]");
			unused.add( resc );

			// we'll wait for "something to happen" -- probably an idle check to
			// complete -- then we'll try again and hope for the best.
			Thread t = Thread.currentThread();
			try
			    {
				otherWaiters.add ( t );
				this.wait( timeout );
				ensureNotBroken();
			    }
			finally
			    { otherWaiters.remove( t ); }
			return checkoutResource( timeout );
		    }

		
		if (isExpired( resc ) || !attemptRefurbishResourceOnCheckout( resc ))
		    {
			removeResource( resc );
			ensureMinResources();
			return checkoutResource( timeout );
		    }
		else
		    {
			asyncFireResourceCheckedOut( resc, managed.size(), unused.size(), excluded.size() );
			if (Debug.DEBUG && Debug.TRACE == Debug.TRACE_MAX) trace();
			return resc;
		    }
	    }
	catch ( ResourceClosedException e ) // one of our async threads died
	    {
		//System.err.println(this + " -- the pool was found to be closed or broken during an attempt to check out a resource.");
		//e.printStackTrace();
		if (logger.isLoggable( MLevel.SEVERE ))
		    logger.log( MLevel.SEVERE, this + " -- the pool was found to be closed or broken during an attempt to check out a resource.", e );

		this.unexpectedBreak();
		throw e;
	    }
	catch ( InterruptedException e )
	    {
// 		System.err.println(this + " -- an attempt to checkout a resource was interrupted: some other thread " +
// 				   "must have either interrupted the Thread attempting checkout, or close() was called on the pool.");
// 		e.printStackTrace();
		if (logger.isLoggable( MLevel.WARNING ))
		    {
			logger.log(MLevel.WARNING, 
				   this + " -- an attempt to checkout a resource was interrupted: some other thread " +
				   "must have either interrupted the Thread attempting checkout, or close() was called on the pool.",
				   e );
		    }
		throw e;
	    }
    }

    public synchronized void checkinResource( Object resc ) 
	throws ResourcePoolException
    {
	try
	    {
		//we permit straggling resources to be checked in 
		//without exception even if we are broken
		if (managed.keySet().contains(resc))
		    doCheckinManaged( resc );
		else if (excluded.contains(resc))
		    doCheckinExcluded( resc );
		else
		    throw new ResourcePoolException("ResourcePool" + (broken ? " [BROKEN!]" : "") + ": Tried to check-in a foreign resource!");
		if (Debug.DEBUG && Debug.TRACE == Debug.TRACE_MAX) trace();
	    }
	catch ( ResourceClosedException e ) // one of our async threads died
	    {
// 		System.err.println(this + 
// 				   " - checkinResource( ... ) -- even broken pools should allow checkins without exception. probable resource pool bug.");
// 		e.printStackTrace();

		if ( logger.isLoggable( MLevel.SEVERE ) )
		    logger.log( MLevel.SEVERE, 
				this + " - checkinResource( ... ) -- even broken pools should allow checkins without exception. probable resource pool bug.", 
				e);

		this.unexpectedBreak();
		throw e;
	    }
    }

    public synchronized void checkinAll()
	throws ResourcePoolException
    {
	try
	    {
		Set checkedOutNotExcluded = new HashSet( managed.keySet() );
		checkedOutNotExcluded.removeAll( unused );
		for (Iterator ii = checkedOutNotExcluded.iterator(); ii.hasNext(); )
		    doCheckinManaged( ii.next() );
		for (Iterator ii = excluded.iterator(); ii.hasNext(); )
		    doCheckinExcluded( ii.next() );
	    }
	catch ( ResourceClosedException e ) // one of our async threads died
	    {
// 		System.err.println(this + 
// 				   " - checkinAll() -- even broken pools should allow checkins without exception. probable resource pool bug.");
// 		e.printStackTrace();

		if ( logger.isLoggable( MLevel.SEVERE ) )
		    logger.log( MLevel.SEVERE,
				this + " - checkinAll() -- even broken pools should allow checkins without exception. probable resource pool bug.",
				e );

		this.unexpectedBreak();
		throw e;
	    }
    }

    public synchronized int statusInPool( Object resc )
	throws ResourcePoolException
    {
	try
	    {
		if ( unused.contains( resc ) )
		    return KNOWN_AND_AVAILABLE;
		else if ( managed.keySet().contains( resc ) || excluded.contains( resc ) )
		    return KNOWN_AND_CHECKED_OUT;
		else
		    return UNKNOWN_OR_PURGED;
	    }
	catch ( ResourceClosedException e ) // one of our async threads died
	    {
// 		e.printStackTrace();
		if ( logger.isLoggable( MLevel.SEVERE ) )
		    logger.log( MLevel.SEVERE, "Apparent pool break.", e );
		this.unexpectedBreak();
		throw e;
	    }
    }

    public synchronized void markBroken(Object resc) 
    {
	try
	    { 
		_markBroken( resc ); 
		ensureMinResources();
	    }
	catch ( ResourceClosedException e ) // one of our async threads died
	    {
		//e.printStackTrace();
		if ( logger.isLoggable( MLevel.SEVERE ) )
		    logger.log( MLevel.SEVERE, "Apparent pool break.", e );
		this.unexpectedBreak();
	    }
    }

    //min is immutable, no need to synchronize
    public int getMinPoolSize()
    { return min; }

    //max is immutable, no need to synchronize
    public int getMaxPoolSize()
    { return max; }

    public synchronized int getPoolSize()
	throws ResourcePoolException
    { return managed.size(); }

    public synchronized void setPoolSize(final int sz)
	throws ResourcePoolException
    {
	try
	    {
		Exception exc = doSetPoolSize(sz);
		if (exc != null)
		    {
			if (exc instanceof RuntimeException)
			    throw (RuntimeException) exc;
			else
			    throw ResourcePoolUtils.convertThrowable(exc);
		    }
	    }
	catch ( ResourceClosedException e ) // one of our async threads died
	    {
		//e.printStackTrace();
		if ( logger.isLoggable( MLevel.SEVERE ) )
		    logger.log( MLevel.SEVERE, "Apparent pool break.", e );
		this.unexpectedBreak();
	    }
    }

//      //i don't think i like the async, no-guarantees approach
//      public synchronized void requestResize( int req_sz )
//      {
//  	if (req_sz > max)
//  	    req_sz = max;
//  	else if (req_sz < min)
//  	    req_sz = min;
//  	int sz = managed.size();
//  	if (req_sz > sz)
//  	    postAcquireUntil( req_sz );
//  	else if (req_sz < sz)
//  	    postRemoveTowards( req_sz );
//      }

    public synchronized int getAvailableCount()
    { return unused.size(); }

    public synchronized int getExcludedCount()
    { return excluded.size(); }

    public synchronized int getAwaitingCheckinCount()
    { return managed.size() - unused.size() + excluded.size(); }

    public synchronized void resetPool()
    {
	try
	    {
		for (Iterator ii = cloneOfManaged().keySet().iterator(); ii.hasNext();)
		    markBrokenNoEnsureMinResources(ii.next());
		ensureMinResources();
	    }
	catch ( ResourceClosedException e ) // one of our async threads died
	    {
		//e.printStackTrace();
		if ( logger.isLoggable( MLevel.SEVERE ) )
		    logger.log( MLevel.SEVERE, "Apparent pool break.", e );
		this.unexpectedBreak();
	    }
    }

    public synchronized void close() 
	throws ResourcePoolException
    {
	//we permit closes when we are already broken, so
	//that resources that were checked out when the break
	//occured can still be cleaned up
	close( true );
    }

    public void finalize() throws Throwable
    {
	//obviously, clients mustn't rely on finalize,
	//but must close pools ASAP after use.
	//System.err.println("finalizing..." + this);

	if (! broken )
	    this.close();
    }

    public void addResourcePoolListener(ResourcePoolListener rpl)
    { 
	if ( asyncEventQueue == null )
	    throw new RuntimeException(this + " does not support ResourcePoolEvents. " +
				       "Probably it was constructed by a BasicResourceFactory configured not to support such events.");
	else
	    rpes.addResourcePoolListener(rpl); 
    }

    public void removeResourcePoolListener(ResourcePoolListener rpl)
    { 
	if ( asyncEventQueue == null )
	    throw new RuntimeException(this + " does not support ResourcePoolEvents. " +
				       "Probably it was constructed by a BasicResourceFactory configured not to support such events.");
	else
	    rpes.removeResourcePoolListener(rpl); 
    }

    private synchronized boolean isForceKillAcquiresPending()
    { return force_kill_acquires; }

    // this is designed as a response to a determination that our resource source is down.
    // rather than declaring ourselves broken in this case (as we did previously), we
    // kill all pending acquisition attempts, but retry on new acqusition requests.
    private synchronized void forceKillAcquires() throws InterruptedException
    {
	Thread t = Thread.currentThread();

	try
	    {
		force_kill_acquires = true;
		this.notifyAll(); //wake up any threads waiting on an acquire, and force them all to die.
		while (acquireWaiters.size() > 0) //we want to let all the waiting acquires die before we unset force_kill_acquires
		    {
			otherWaiters.add( t ); 
			this.wait();
		    }
		force_kill_acquires = false;
	    }
	finally
	    { otherWaiters.remove( t ); }
    }

    //same as close(), but we do not destroy checked out
    //resources
    private synchronized void unexpectedBreak()
    {
	if ( logger.isLoggable( MLevel.SEVERE ) )
	    logger.log( MLevel.SEVERE, this + " -- Unexpectedly broken!!!", new ResourcePoolException("Unexpected Break Stack Trace!") );
	close( false );
    }

    private void postAcquireUntil(int num) 
    {
// 	System.err.println("...postAcquireUntil( " +  num + " )"); 
// 	new Exception("...dumping stack trace").printStackTrace();
	taskRunner.postRunnable(new AcquireTask(num)); 
    }

    private void postRemoveTowards(int num) 
    {
	//System.err.println("...postRemoveTowards(" + num + ")");
	taskRunner.postRunnable(new RemoveTask(num)); 
    }

    private boolean canFireEvents()
    { return (! broken && asyncEventQueue != null); }

    private void asyncFireResourceAcquired( final Object       resc,
					    final int          pool_size,
					    final int          available_size,
					    final int          removed_but_unreturned_size )
    {
	if ( canFireEvents() )
	    {
		Runnable r = new Runnable()
		    {
			public void run()
			{rpes.fireResourceAcquired(resc, pool_size, available_size, removed_but_unreturned_size);}
		    };
		asyncEventQueue.postRunnable(r);
	    }
    }

    private void asyncFireResourceCheckedIn( final Object       resc,
					     final int          pool_size,
					     final int          available_size,
					     final int          removed_but_unreturned_size )
    {
	if ( canFireEvents() )
	    {
		Runnable r = new Runnable()
		    {
			public void run()
			{rpes.fireResourceCheckedIn(resc, pool_size, available_size, removed_but_unreturned_size);}
		    };
		asyncEventQueue.postRunnable(r);
	    }
    }

    private void asyncFireResourceCheckedOut( final Object       resc,
					      final int          pool_size,
					      final int          available_size,
					      final int          removed_but_unreturned_size )
    {
	if ( canFireEvents() )
	    {
		Runnable r = new Runnable()
		    {
			public void run()
			{rpes.fireResourceCheckedOut(resc,pool_size,available_size,removed_but_unreturned_size);}
		    };
		asyncEventQueue.postRunnable(r);
	    }
    }

    private void asyncFireResourceRemoved( final Object       resc,
					   final boolean      checked_out_resource,
					   final int          pool_size,
					   final int          available_size,
					   final int          removed_but_unreturned_size )
    {
	if ( canFireEvents() )
	    {
		//System.err.println("ASYNC RSRC REMOVED");
		//new Exception().printStackTrace();
		Runnable r = new Runnable()
		    {
			public void run()
			{
			    rpes.fireResourceRemoved(resc, checked_out_resource,
						     pool_size,available_size,removed_but_unreturned_size);
			}
		    };
		asyncEventQueue.postRunnable(r);
	    }
    }
	
    private void destroyResource(final Object resc)
    { destroyResource( resc, false ); }
    
    private void destroyResource(final Object resc, boolean synchronous)
    {
	Runnable r = new Runnable()
	    {
		public void run()
		{
		    try { mgr.destroyResource(resc); }
		    catch ( Exception e )
			{
			    if ( logger.isLoggable( MLevel.WARNING ) )
				logger.log( MLevel.WARNING, "Failed to destroy resource: " + resc, e );

// 			    System.err.println("Failed to destroy resource: " + resc);
// 			    e.printStackTrace();
			}
		}
	    };
	if ( synchronous )
	    r.run();
	else
	    taskRunner.postRunnable( r );
    }

    //this method NEED NOT be invoked from a synchronized
    //block!!!!
    private void acquireUntil(int num) throws Exception
    {
	int msz;
	do
	    {
		synchronized(this)
		    { 
			msz = managed.size(); 
			if (msz < num)
			    assimilateResource();
		    }

		//if there is a Thread waiting on
		//this resource, try give it up before
		//acquiring more!
		Thread.currentThread().yield();
	    }
	while (msz < num);
    }

//      private void acquireUntil(int num) throws Exception
//      {
//  	int msz = managed.size();
//  	for (int i = msz; i < num; ++i)
//  	    assimilateResource();
//      }

    //the following methods should only be invoked from 
    //sync'ed methods / blocks...

//     private Object useUnusedButNotInIdleCheck()
//     {
// 	for (Iterator ii = unused.iterator(); ii.hasNext(); )
// 	    {
// 		Object maybeOut = ii.next();
// 		if (! idleCheckResources.contains( maybeOut ))
// 		    {
// 			ii.remove();
// 			return maybeOut;
// 		    }
// 	    }
// 	throw new RuntimeException("Internal Error -- the pool determined that it did have a resource available for checkout, but was unable to find one.");
//     }

//     private int actuallyAvailable()
//     { return unused.size() - idleCheckResources.size(); }

    private void markBrokenNoEnsureMinResources(Object resc) 
    {
	try
	    { 
		_markBroken( resc ); 
	    }
	catch ( ResourceClosedException e ) // one of our async threads died
	    {
		//e.printStackTrace();
		if ( logger.isLoggable( MLevel.SEVERE ) )
		    logger.log( MLevel.SEVERE, "Apparent pool break.", e );
		this.unexpectedBreak();
	    }
    }

    private void _markBroken( Object resc )
    {
	if ( unused.contains( resc ) )
	    removeResource( resc ); 
	else
	    excludeResource( resc );
    }

    //DEBUG
    //Exception firstClose = null;

    private void close( boolean close_checked_out_resources )
    {

	if (! broken ) //ignore repeated calls to close
	    {
		//DEBUG
		//firstClose = new Exception("First close() -- debug stack trace [CRAIG]");
		//firstClose.printStackTrace();
		
		this.broken = true;
		Collection cleanupResources = ( close_checked_out_resources ? (Collection) cloneOfManaged().keySet() : (Collection) cloneOfUnused() );
		if ( cullTask != null )
		    cullTask.cancel();
		if (idleRefurbishTask != null)
		    idleRefurbishTask.cancel();
		for (Iterator ii = cleanupResources.iterator(); ii.hasNext();)
		    {
			try
			    {
				Object resc = ii.next();
				if (unused.contains( resc )) //same logic as _markBroken(...), but removes have to be synchronous
				    removeResource(resc, true);
				else
				    excludeResource( resc );
			    }
			catch (Exception e)
			    {
				if (Debug.DEBUG) 
				    {
					//e.printStackTrace();
					if ( logger.isLoggable( MLevel.FINE ) )
					    logger.log( MLevel.FINE, "BasicResourcePool -- A resource couldn't be cleaned up on close()", e );
				    }
			    }
		    }
		for (Iterator ii = acquireWaiters.iterator(); ii.hasNext(); )
		    ((Thread) ii.next()).interrupt();
		for (Iterator ii = otherWaiters.iterator(); ii.hasNext(); )
		    ((Thread) ii.next()).interrupt();
		if (factory != null)
		    factory.markBroken( this );
		// System.err.println(this + " closed.");
	    }
	else
	    {
		if ( logger.isLoggable( MLevel.WARNING ) )
		    logger.warning(this + " -- close() called multiple times.");
		    //System.err.println(this + " -- close() called multiple times.");

		//DEBUG
		//firstClose.printStackTrace();
		//new Exception("Repeat close() [CRAIG]").printStackTrace();
	    }
    }

    private void doCheckinManaged( final Object resc ) throws ResourcePoolException
    {
	if (unused.contains(resc))
	    {
		if ( Debug.DEBUG )
		    throw new ResourcePoolException("Tried to check-in an already checked-in resource: " + resc);
	    }
	else
	    {
		Runnable doMe = new Runnable()
		    {
			public void run()
			{
			    synchronized( BasicResourcePool.this )
				{
				    boolean resc_okay = attemptRefurbishResourceOnCheckin( resc );
				    if ( resc_okay )
					{
					    unused.add( resc );
					    if (! age_is_absolute ) //we need to reset the clock, 'cuz we are counting idle time
						managed.put( resc, new Date() );
					}
				    else
					{
					    removeResource( resc );
					    ensureMinResources();
					}
				    
				    asyncFireResourceCheckedIn( resc, managed.size(), unused.size(), excluded.size() );
				    BasicResourcePool.this.notifyAll();
				}
			}
		    };
		taskRunner.postRunnable( doMe );
	    }
    }

    private void doCheckinExcluded( Object resc )
    {
	excluded.remove(resc);
	destroyResource(resc);
    }

    private Exception doSetPoolSize(int sz)
    {
	try
	    {
		if (sz > max)
		    {
			throw new IllegalArgumentException("Requested size [" + sz + 
							   "] is greater than max [" + max +
							   "].");
		    } 
		else if (sz < min)
		    {
			throw new IllegalArgumentException("Requested size [" + sz + 
							   "] is less than min [" + min +
							   "].");
		    }
		int msz = managed.size(); 
		if (sz > msz)
		    acquireUntil( sz );
		else if (sz < msz)
		    {
			int num_to_cull = msz - sz;
			int usz = unused.size(); 
			int num_from_unused = Math.min( num_to_cull, usz );
			for (int i = 0; i < num_from_unused; ++i)
			    removeResource( unused.get(0) );
			int num_outstanding_to_cull = num_to_cull - num_from_unused;
			Iterator ii = cloneOfManaged().keySet().iterator();
			for (int i = 0; i < num_outstanding_to_cull; ++i)
			    excludeResource( ii.next() );
		    }
		return null;
	    }
	catch (Exception e)
	    { return e; }
	finally
	    { this.notifyAll(); }
    }

    private void postAcquireMore()
    { 
  	int msz = managed.size();
	int pending_acquires = pendingAcquiresCounter.getValue();

	// we want to get at least inc more, and we want enough 
	// so we get one for each request for more resources. Previous
	// requests are accounted for in pending_acquires; we add one
	// for this request.

	int num_desired = msz + Math.max( inc, pending_acquires + 1 );
	postAcquireUntil( Math.min( num_desired, max ) );
    }

    // by the semantics of wait( timeout ), 0 waits forever
//     private void awaitIdleCheck(long timeout) throws InterruptedException, TimeoutException
//     {
// 	Thread t = Thread.currentThread();
// 	interruptableWaiters.add( t );

// 	int num_in_check;
// 	long start = ( timeout > 0 ? System.currentTimeMillis() : -1);
// 	while( (num_in_check = idleCheckResources.size()) != 0)
// 	    {
// 		this.wait(timeout);
// 		if ( idleCheckResources.size() < num_in_check ) //okay, what we were waiting for happened...
// 		    return;
// 		else if (timeout > 0 && System.currentTimeMillis() - start > timeout)
// 		    throw new TimeoutException("internal -- timeout at awaitIdleCheck()");
// 	    }

// 	interruptableWaiters.remove( t );
//     }

    /*
     * by the semantics of wait(), a timeout of zero means forever.
     */
    private void awaitAcquire(long timeout) throws InterruptedException, TimeoutException, ResourcePoolException
    {
	if (force_kill_acquires)
	    throw new ResourcePoolException("A ResourcePool cannot acquire a new resource -- the factory or source appears to be down.");

	Thread t = Thread.currentThread();
	try
	    {
		acquireWaiters.add( t );
		
		int avail;
		long start = ( timeout > 0 ? System.currentTimeMillis() : -1);
		if (Debug.DEBUG && Debug.TRACE == Debug.TRACE_MAX)
		    {
			if ( logger.isLoggable( MLevel.FINE ) )
			    logger.fine("awaitAvailable(): " + 
					(exampleResource != null ? 
					 exampleResource : 
					 "[unknown]") );
			trace();
		    }
		while ((avail = unused.size()) == 0) 
		    {
			// the if case below can only occur when 1) a user attempts a
			// checkout which would provoke an acquire; 2) this
			// increments the pending acquires, so we go to the
			// wait below without provoking postAcquireMore(); 3)
			// the resources are acquired; 4) external management
			// of the pool (via for instance unpoolResource() 
			// depletes the newly acquired resources before we
			// regain this' monitor; 5) we fall into wait() with
			// no acquires being scheduled, and perhaps a managed.size()
			// of zero, leading to deadlock. This could only occur in
			// fairly pathological situations where the pool is being
			// externally forced to a very low (even zero) size, but 
			// since I've seen it, I've fixed it.
			if (pendingAcquiresCounter.getValue() == 0)
			    postAcquireMore();
			
			this.wait(timeout);
			if (timeout > 0 && System.currentTimeMillis() - start > timeout)
			    throw new TimeoutException("internal -- timeout at awaitAcquire()");
			if (force_kill_acquires)
			    throw new CannotAcquireResourceException("A ResourcePool could not acquire a resource from its primary factory or source.");
			ensureNotBroken();
		    }
	    }
	finally
	    {
		acquireWaiters.remove( t );
		if (acquireWaiters.size() == 0)
		    this.notifyAll();
	    }
    }

    private void assimilateResource() throws Exception
    {
	Object resc = mgr.acquireResource();
	managed.put(resc, new Date());
	unused.add(resc);
	//System.err.println("assimilate resource... unused: " + unused.size());
	asyncFireResourceAcquired( resc, managed.size(), unused.size(), excluded.size() );
	this.notifyAll();
	if (Debug.DEBUG && Debug.TRACE == Debug.TRACE_MAX) trace();
	if (Debug.DEBUG && exampleResource == null)
	    exampleResource = resc;
    }

    private void removeResource(Object resc)
    { removeResource( resc, false ); }

    private void removeResource(Object resc, boolean synchronous)
    {
	managed.remove(resc);
	unused.remove(resc);
	destroyResource(resc, synchronous);
	asyncFireResourceRemoved( resc, false, managed.size(), unused.size(), excluded.size() );
	if (Debug.DEBUG && Debug.TRACE == Debug.TRACE_MAX) trace();
	//System.err.println("RESOURCE REMOVED!");
    }

    //when we want to conceptually remove a checked
    //out resource from the pool
    private void excludeResource(Object resc)
    {
	managed.remove(resc);
	excluded.add(resc);
	if (Debug.DEBUG && unused.contains(resc) )
	    throw new InternalError( "We should only \"exclude\" checked-out resources!" );
	asyncFireResourceRemoved( resc, true, managed.size(), unused.size(), excluded.size() );
    }

    private void removeTowards( int new_sz )
    {
	int num_to_remove = managed.size() - new_sz;
	int count = 0;
	for (Iterator ii = cloneOfUnused().iterator(); 
	     ii.hasNext() && count < num_to_remove; 
	     ++count)
	    {
		Object resc = ii.next();
		removeResource( resc );
	    }
    }

    private void cullExpiredAndUnused()
    {
	for ( Iterator ii = cloneOfUnused().iterator(); ii.hasNext(); )
	    {
		Object resc = ii.next();
		if ( isExpired( resc ) )
		    {
			if (Debug.DEBUG && logger.isLoggable( MLevel.FINER ))
			    logger.log( MLevel.FINER, "Removing expired resource: " + resc + " [" + this + "]");
			//System.err.println("c3p0-JENNIFER: removing expired resource: " + resc + " [" + this + "]");
			removeResource( resc );
		    }
	    }
	ensureMinResources();
    }

    private void checkIdleResources()
    {
	List u = cloneOfUnused();
	for ( Iterator ii = u.iterator(); ii.hasNext(); )
	    {
		Object resc = ii.next();
		if ( idleCheckResources.add( resc ) )
		    taskRunner.postRunnable( new AsyncTestIdleResourceTask( resc ) );
	    }

	if (Debug.DEBUG && Debug.TRACE == Debug.TRACE_MAX) trace();
    }

    private boolean isExpired( Object resc )
    {
	if (max_resource_age > 0)
	    {
		Date d = (Date) managed.get( resc );
		long now = System.currentTimeMillis();
		long age = now - d.getTime();
		boolean expired = ( age > max_resource_age );

// 		if (expired)
// 		    System.err.println("c3p0-JENNIFER: EXPIRED resource: " + resc + " ---> age: " + age + "   max: " + max_resource_age + " [" + this + "]");
// 		else
// 		    System.err.println("c3p0-JENNIFER: resource age is okay: " + resc + " ---> age: " + age + "   max: " + max_resource_age + " [" + this + "]");

		if ( Debug.DEBUG && Debug.TRACE == Debug.TRACE_MAX && logger.isLoggable( MLevel.FINEST ) )
		    {
			if (expired)
			    logger.log(MLevel.FINEST, 
				       "EXPIRED resource: " + resc + " ---> age: " + age + 
				       "   max: " + max_resource_age + " [" + this + "]");
			else
			    logger.log(MLevel.FINEST, 
				       "resource age is okay: " + resc + " ---> age: " + age + 
				       "   max: " + max_resource_age + " [" + this + "]");
		    }
		return expired;
	    }
	else
	    return false; 
    }

//     private boolean resourcesInIdleCheck()
//     { return idleCheckresources.size() > 0; }

//     private int countAvailable()
//     { return unused.size() - idleCheckResources.size(); }

    private void ensureStartResources()
    { this.postAcquireUntil( Math.max(start, min) ); }

    private void ensureMinResources()
    {
	if (managed.size() < min)
	    this.postAcquireUntil( min ); 
    }

    private boolean attemptRefurbishResourceOnCheckout( Object resc )
    {
	try
	    { 
		mgr.refurbishResourceOnCheckout(resc); 
		return true;
	    }
	catch (Exception e)
	    {
		//uh oh... bad resource...
		if (Debug.DEBUG) 
		    {
			//e.printStackTrace();
			if (logger.isLoggable( MLevel.FINE ))
			    logger.log( MLevel.FINE, "A resource could not be refurbished on checkout.", e );
		    }
		return false;
	    }
    }

    private boolean attemptRefurbishResourceOnCheckin( Object resc )
    {
	try
	    { 
		mgr.refurbishResourceOnCheckin(resc); 
		return true;
	    }
	catch (Exception e)
	    {
		//uh oh... bad resource...
		if (Debug.DEBUG) 
		    {
			//e.printStackTrace();
			if (logger.isLoggable( MLevel.FINE ))
			    logger.log( MLevel.FINE, "A resource could not be refurbished on checkin.", e );
		    }
		return false;
	    }
    }

    private void ensureNotBroken() throws ResourcePoolException
    {
	if (broken) 
	    throw new ResourcePoolException("Attempted to use a closed or broken resource pool");
    }

    private void trace()
    {
	if ( logger.isLoggable( MLevel.FINEST ) )
	    {
		String exampleResStr = ( exampleResource == null ?
					 "" :
					 " (e.g. " + exampleResource +")");
		logger.finest("trace " + this + " [managed: " + managed.size() + ", " +
			      "unused: " + unused.size() + ", excluded: " +
			      excluded.size() + ']' + exampleResStr );
	    }
    }

    private final HashMap cloneOfManaged()
    { return (HashMap) managed.clone(); }

    private final LinkedList cloneOfUnused()
    { return (LinkedList) unused.clone(); }

    private final HashSet cloneOfExcluded()
    { return (HashSet) excluded.clone(); }

    /*
     *  task we post to separate thread to acquire
     *  pooled resources
     */
    class AcquireTask implements Runnable
    {
	boolean success = false;
	int     num;

	public AcquireTask(int num)
	{ 
	    this.num = num; 
	    pendingAcquiresCounter.increment();
	}
	
	public void run()
	{
	    try
		{
		    for (int i = 0; shouldTry( i ); ++i)
			{
			    try
				{
				    if (i > 0)
					Thread.sleep(acq_attempt_delay); 
				    
				    //we don't want this call to be sync'd
				    //on the pool, so that a waiting Thread
				    //can pull the first resource we acquire
				    //without awaiting them all.
				    acquireUntil( num );
				    
				    success = true;
				}
			    catch (Exception e)
				{
				    if (Debug.DEBUG) 
					{
					    //e.printStackTrace();
					    if (logger.isLoggable( MLevel.FINE ))
						logger.log( MLevel.FINE, "An exception occurred while acquiring a resource.", e );
					}
				}
			}
		    if (!success) 
			{
			    if ( logger.isLoggable( MLevel.WARNING ) )
				{
				    logger.log( MLevel.WARNING,
						this + " -- Acquisition Attempt Failed!!! Clearing pending acquires. " +
						"While trying to acquire a needed new resource, we failed " +
						"to succeed more than the maximum number of allowed " +
						"acquisition attempts (" + num_acq_attempts + ")." );
				}
			    if (break_on_acquisition_failure)
				{
				    //System.err.println("\tTHE RESOURCE POOL IS PERMANENTLY BROKEN!");
				    if ( logger.isLoggable( MLevel.SEVERE ) )
					logger.severe("THE RESOURCE POOL IS PERMANENTLY BROKEN! [" + this + "]");
				    unexpectedBreak();
				}
			    else
				forceKillAcquires();
			}
		}
	    catch ( ResourceClosedException e ) // one of our async threads died
		{
		    //e.printStackTrace();
		    if ( Debug.DEBUG )
			{
			    if ( logger.isLoggable( MLevel.FINE ) )
				logger.log( MLevel.FINE, "a resource pool async thread died.", e );
			}
		    unexpectedBreak();
		}
	    catch (InterruptedException e) //from force kill acquires
		{
		    if ( logger.isLoggable( MLevel.WARNING ) )
			{
			    logger.log( MLevel.WARNING,
					BasicResourcePool.this + " -- Thread unexpectedly interrupted while waiting for stale acquisition attempts to die.",
					e );
			}

// 		    System.err.println(BasicResourcePool.this + " -- Thread unexpectedly interrupted while waiting for stale acquisition attempts to die.");
// 		    e.printStackTrace();
		}
	    finally
		{ pendingAcquiresCounter.decrement(); }
	}

	private boolean shouldTry(int attempt_num)
	{
	    //try if we haven't already succeeded
	    //and someone hasn't signalled that our resource source is down
	    //and not max attempts is set,
	    //or we are less than the set limit
	    return 
		!success && 
		!isForceKillAcquiresPending() &&
		(num_acq_attempts <= 0 || attempt_num < num_acq_attempts);
	}
    }

    class CullTask extends TimerTask
    {
	public void run()
	{
	    try
		{
		    //System.err.println("c3p0-JENNIFER: checking for expired resources - " + new Date() + " [" + BasicResourcePool.this + "]");
		    if (Debug.DEBUG && Debug.TRACE >= Debug.TRACE_MED && logger.isLoggable( MLevel.FINER ))
			logger.log( MLevel.FINER, "Checking for expired resources - " + new Date() + " [" + BasicResourcePool.this + "]");
		    synchronized ( BasicResourcePool.this )
			{ cullExpiredAndUnused(); }
		}
	    catch ( ResourceClosedException e ) // one of our async threads died
		{
// 		    e.printStackTrace();
		    if ( Debug.DEBUG )
			{
			    if ( logger.isLoggable( MLevel.FINE ) )
				logger.log( MLevel.FINE, "a resource pool async thread died.", e );
			}
		    unexpectedBreak();
		}
	}
    }

    class RemoveTask implements Runnable
    {
	int     num;

	public RemoveTask(int num)
	{ this.num = num; }
	
	public void run()
	{ 
	    try
		{
		    synchronized ( BasicResourcePool.this )
			{ removeTowards( num ); }	
		}
	    catch ( ResourceClosedException e ) // one of our async threads died
		{
// 		    e.printStackTrace();
		    if ( Debug.DEBUG )
			{
			    if ( logger.isLoggable( MLevel.FINE ) )
				logger.log( MLevel.FINE, "a resource pool async thread died.", e );
			}
		    unexpectedBreak();
		}
	}
    }

    // this is run by a single-threaded timer, so we don't have
    // to worry about multiple threads executing the task at the same 
    // time 
    class CheckIdleResourcesTask extends TimerTask
    {
	public void run()
	{
	    try
		{
		    //System.err.println("c3p0-JENNIFER: refurbishing idle resources - " + new Date() + " [" + BasicResourcePool.this + "]");
		    if (Debug.DEBUG && Debug.TRACE >= Debug.TRACE_MED && logger.isLoggable(MLevel.FINER))
			logger.log(MLevel.FINER, "Refurbishing idle resources - " + new Date() + " [" + BasicResourcePool.this + "]");
		    synchronized ( BasicResourcePool.this )
			{ checkIdleResources(); }
		}
	    catch ( ResourceClosedException e ) // one of our async threads died
		{
		    //e.printStackTrace();
		    if ( Debug.DEBUG )
			{
			    if ( logger.isLoggable( MLevel.FINE ) )
				logger.log( MLevel.FINE, "a resource pool async thread died.", e );
			}
		    unexpectedBreak();
		}
	}
    }

    class AsyncTestIdleResourceTask implements Runnable
    {
	// unchanging after ctor
	Object resc;

	// protected by this' lock
	boolean pending = true;
	boolean failed;

	AsyncTestIdleResourceTask( Object resc )
	{ this.resc = resc; }

// 	synchronized boolean pending()
// 	{ return pending; }

// 	synchronized boolean failed()
// 	{
// 	    if (pending)
// 		throw new RuntimeException(this + " You bastard! You can't check if the test failed wile it's pending!");
// 	    return 
// 		failed;
// 	}

// 	synchronized void unpend()
// 	{ pending = false; }

// 	synchronized void setFailed( boolean f )
// 	{ this.failed = f; }

	public void run()
	{
	    try
		{
		    boolean failed;
		    try
			{ 
			    mgr.refurbishIdleResource( resc ); 
			    failed = false;

			    //trace();
			    //Thread.sleep(1000); //DEBUG: make sure collision detection works
			}
		    catch ( Exception e )
			{
			    //System.err.println("c3p0: An idle resource is broken and will be purged.");
			    //System.err.print("c3p0 [broken resource]: ");
			    //e.printStackTrace();

			    if ( logger.isLoggable( MLevel.WARNING ) )
				logger.log( MLevel.WARNING, "BasicResourcePool: An idle resource is broken and will be purged.", e);

			    failed = true;
			}
		    
		    synchronized (BasicResourcePool.this)
			{
			    if ( failed )
				{
				    if ( managed.keySet().contains( resc ) ) //resc might have been culled as expired while we tested
					{
					    removeResource( resc ); 
					    ensureMinResources();
					}
				}
			}
		}
	    finally
		{
		    synchronized (BasicResourcePool.this)
			{
			    idleCheckResources.remove( resc );
			    BasicResourcePool.this.notifyAll();
			}
		}
	}
    }

//     static class CheckInProgressResourceHolder
//     {
// 	Object checkResource;

// 	public synchronized void setCheckResource( Object resc )
// 	{ 
// 	    this.checkResource = resc; 
// 	    this.notifyAll();
// 	}

// 	public void unsetCheckResource()
// 	{ setCheckResource( null ); }

// 	/**
// 	 * @return true if we actually had to wait
// 	 */
// 	public synchronized boolean awaitNotInCheck( Object resc )
// 	{
// 	    boolean had_to_wait = false;
// 	    boolean set_interrupt = false;
// 	    while ( checkResource == resc )
// 		{
// 		    try
// 			{
// 			    had_to_wait = true;
// 			    this.wait(); 
// 			}
// 		    catch ( InterruptedException e )
// 			{ 
// 			    e.printStackTrace();
// 			    set_interrupt = true;
// 			}
// 		}
// 	    if ( set_interrupt )
// 		Thread.currentThread().interrupt();
// 	    return had_to_wait;
// 	}
//     }
}
