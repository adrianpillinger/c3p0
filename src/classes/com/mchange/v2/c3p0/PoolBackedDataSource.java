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


package com.mchange.v2.c3p0;

import java.io.*;
import java.sql.*;
import javax.sql.*;
import com.mchange.v2.c3p0.impl.*;
import com.mchange.v2.log.*;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyVetoException;
import java.beans.VetoableChangeListener;
import java.beans.PropertyChangeListener;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;

public final class PoolBackedDataSource extends PoolBackedDataSourceBase implements PooledDataSource
{
    final static MLogger logger = MLog.getLogger( PoolBackedDataSource.class );
    
    final static String NO_CPDS_ERR_MSG =
       "Attempted to use an uninitialized PoolBackedDataSource. " +
       "Please call setConnectionPoolDataSource( ... ) to initialize.";

    //MT: unchanged post-ctor -- Ser: NOT transient... see read/writeObject() below
    ComboPooledDataSource parent;

    //MT: protected by this' lock
    transient C3P0PooledConnectionPoolManager poolManager;
    transient boolean is_closed = false;
    //MT: end protected by this' lock

    public PoolBackedDataSource()
    { this( null ); }

    PoolBackedDataSource( ComboPooledDataSource parent )
    {
	this.parent = parent;

	PropertyChangeListener l = new PropertyChangeListener()
	    {
		public void propertyChange( PropertyChangeEvent evt )
		{ resetPoolManager(); }
	    };
	this.addPropertyChangeListener( l );

	//this.setNested( parent != null );

	C3P0Registry.register( this );
    }

    ComboPooledDataSource owner()
    { return parent; }

// Commented out method is just super.getReference() with a lot of extra printing
//
//     public javax.naming.Reference getReference() throws javax.naming.NamingException
//     {
// 	System.err.println("getReference()!!!!");
// 	new Exception("PRINT-STACK-TRACE").printStackTrace();
// 	javax.naming.Reference out = super.getReference();
// 	System.err.println(out);
// 	return out;
//     }

    //implementation of javax.sql.DataSource
    public Connection getConnection() throws SQLException
    {
	PooledConnection pc = getPoolManager().getPool().checkoutPooledConnection();
	return pc.getConnection();
    }

    public Connection getConnection(String username, String password) throws SQLException
    { 
	PooledConnection pc = getPoolManager().getPool(username, password).checkoutPooledConnection();
	return pc.getConnection();
    }

    public PrintWriter getLogWriter() throws SQLException
    { return assertCpds().getLogWriter(); }

    public void setLogWriter(PrintWriter out) throws SQLException
    { assertCpds().setLogWriter( out ); }

    public int getLoginTimeout() throws SQLException
    { return assertCpds().getLoginTimeout(); }

    public void setLoginTimeout(int seconds) throws SQLException
    { assertCpds().setLoginTimeout( seconds ); }

    //implementation of com.mchange.v2.c3p0.PoolingDataSource
    public int getNumConnections() throws SQLException
    { return getPoolManager().getPool().getNumConnections(); }

    public int getNumIdleConnections() throws SQLException
    { return getPoolManager().getPool().getNumIdleConnections(); }

    public int getNumBusyConnections() throws SQLException
    { return getPoolManager().getPool().getNumBusyConnections(); }

    public int getNumUnclosedOrphanedConnections() throws SQLException
    { return getPoolManager().getPool().getNumUnclosedOrphanedConnections(); }

    public int getNumConnectionsDefaultUser() throws SQLException
    { return getNumConnections();}

    public int getNumIdleConnectionsDefaultUser() throws SQLException
    { return getNumIdleConnections();}

    public int getNumBusyConnectionsDefaultUser() throws SQLException
    { return getNumBusyConnections();}

    public int getNumUnclosedOrphanedConnectionsDefaultUser() throws SQLException
    { return getNumUnclosedOrphanedConnections();}

    public void softResetDefaultUser() throws SQLException
    { getPoolManager().getPool().reset(); }

    public int getNumConnections(String username, String password) throws SQLException
    { return getPoolManager().getPool(username, password).getNumConnections(); }

    public int getNumIdleConnections(String username, String password) throws SQLException
    { return getPoolManager().getPool(username, password).getNumIdleConnections(); }

    public int getNumBusyConnections(String username, String password) throws SQLException
    { return getPoolManager().getPool(username, password).getNumBusyConnections(); }

    public int getNumUnclosedOrphanedConnections(String username, String password) throws SQLException
    { return getPoolManager().getPool(username, password).getNumUnclosedOrphanedConnections(); }

    public void softReset(String username, String password) throws SQLException
    { getPoolManager().getPool(username, password).reset(); }

    public int getNumBusyConnectionsAllUsers() throws SQLException
    { return getPoolManager().getNumBusyConnectionsAllAuths(); }

    public int getNumIdleConnectionsAllUsers() throws SQLException
    { return getPoolManager().getNumIdleConnectionsAllAuths(); }

    public int getNumConnectionsAllUsers() throws SQLException
    { return getPoolManager().getNumConnectionsAllAuths(); }

    public int getNumUnclosedOrphanedConnectionsAllUsers() throws SQLException
    { return getPoolManager().getNumUnclosedOrphanedConnectionsAllAuths(); }

    public void softResetAllUsers() throws SQLException
    { getPoolManager().softResetAllAuths(); }

    public int getNumUserPools() throws SQLException
    { return getPoolManager().getNumManagedAuths(); }

//
// leaving getAllUsers() unimplemented for the moment out of security considerations
//

//     public Collection getAllUsers() throws SQLException
//     {
// 	LinkedList out = new LinkedList();
// 	Set auths = getPoolManager().getManagedAuths();
// 	for ( Iterator ii = auths.iterator(); ii.hasNext(); )
// 	    out.add( ((DbAuth) ii.next()).getUser() );
// 	return Collections.unmodifiableList( out );
//     }

    public synchronized void hardReset()
    {
	C3P0PooledConnectionPoolManager forceDestroyMe = poolManager;
	resetPoolManager(); 
	forceDestroyMe.forceDestroy();
    }

    public void close()
    { close( false ); }

    public synchronized void close(boolean force_destroy)
    { 
	C3P0PooledConnectionPoolManager forceDestroyMe = (force_destroy ? poolManager : null );
	resetPoolManager(); 
	if ( force_destroy )
	    forceDestroyMe.forceDestroy();

	is_closed = true;

	if (Debug.DEBUG && Debug.TRACE == Debug.TRACE_MAX)
	    {
// 		System.err.println( this.getClass().getName() + '@' + Integer.toHexString( System.identityHashCode( this ) ) +
// 				    " has been closed. force_destroy == " + force_destroy );
// 		new Exception("Debug -- PoolBackedDataSource.close() stack trace:").printStackTrace();
 		logger.log(MLevel.FINEST, 
			   this.getClass().getName() + '@' + Integer.toHexString( System.identityHashCode( this ) ) +
			   " has been closed. force_destroy == " + force_destroy,
			   new Exception("Debug -- PoolBackedDataSource.close() stack trace."));
	    }
    }

    //other code
    public synchronized void resetPoolManager() //used by other, wrapping datasources in package, and in mbean package
    {
	if ( poolManager != null )
	    {
		poolManager.unregisterActiveClient( this );
		poolManager = null;
	    }
     }

     private synchronized ConnectionPoolDataSource assertCpds() throws SQLException
     {
	 if ( is_closed )
	     throw new SQLException(this + " has been closed() -- you can no longer use it.");

	 ConnectionPoolDataSource out = this.getConnectionPoolDataSource();
         if ( out == null )
           throw new SQLException(NO_CPDS_ERR_MSG);
         return out;
     }

     private synchronized C3P0PooledConnectionPoolManager getPoolManager() throws SQLException
     {
	if (poolManager == null)
	    {
		ConnectionPoolDataSource cpds = assertCpds();
		String cpdsIdTkn = (cpds instanceof IdentityTokenized ? ((IdentityTokenized) cpds).getIdentityToken() : C3P0ImplUtils.identityToken( cpds ));
		poolManager = C3P0PooledConnectionPoolManager.find(this.getIdentityToken(), 
								   cpds,
								   cpdsIdTkn,
								   this.getNumHelperThreads());
		poolManager.registerActiveClient( this );
// 		if (Debug.DEBUG && Debug.TRACE > Debug.TRACE_NONE)
// 		    System.err.println("Initializing c3p0 pool... " + this.toString() /* + "; using pool manager: " + poolManager */);
		//new Exception("PRINT STACK TRACE").printStackTrace();
		if (logger.isLoggable(MLevel.INFO))
		    logger.info("Initializing c3p0 pool... " + 
				(parent == null ? this.toString() : parent.toString()) /* + "; using pool manager: " + poolManager */);
	    }
        return poolManager;	    
     }

    
    // Serialization stuff
    private static final long serialVersionUID = 1;
    private static final short VERSION = 0x0001;
    
    private void writeObject( ObjectOutputStream oos ) throws IOException
    {
	oos.writeShort( VERSION );
	oos.writeObject( parent );
    }
	
    private void readObject( ObjectInputStream ois ) throws IOException, ClassNotFoundException
    {
	short version = ois.readShort();
	switch (version)
	    {
	    case VERSION:
		this.parent = (ComboPooledDataSource) ois.readObject();
		break;
	    default:
		throw new IOException("Unsupported Serialized Version: " + version);
	    }
    }
}
