/*******************************************************************************
 * Copyright (c) 2016 AT&T Intellectual Property. All rights reserved.
 *******************************************************************************/
package com.att.dao;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.List;

import com.att.authz.env.AuthzTrans;
import com.att.authz.layer.Result;
import com.att.dao.aaf.cass.Status;
import com.att.inno.env.TransStore;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;

/**
 * AbsCassDAO
 *
 * Deal with the essentials of Interaction with Cassandra DataStore for all Cassandra DAOs
 *
 *
 * @param <DATA>
 */
public class CassDAOImpl<TRANS extends TransStore,DATA> extends AbsCassDAO<TRANS, DATA> implements DAO<TRANS,DATA> {
	public static final String USER_NAME = "__USER_NAME__";
	protected static final String CREATE_SP = "CREATE ";
	protected static final String UPDATE_SP = "UPDATE ";
	protected static final String DELETE_SP = "DELETE ";
	protected static final String SELECT_SP = "SELECT ";

	protected final String C_TEXT = getClass().getSimpleName() + " CREATE";
	protected final String R_TEXT = getClass().getSimpleName() + " READ";
	protected final String U_TEXT = getClass().getSimpleName() + " UPDATE";
	protected final String D_TEXT = getClass().getSimpleName() + " DELETE";
	private String table;
	
	protected final ConsistencyLevel readConsistency,writeConsistency;
	
	// Setteable only by CachedDAO
	protected Cached<?, ?> cache;

	/**
	 * A Constructor from the originating Cluster.  This DAO will open the Session at need,
	 * and shutdown the session when "close()" is called.
	 *
	 * @param cluster
	 * @param keyspace
	 * @param dataClass
	 */
	public CassDAOImpl(TRANS trans, String name, Cluster cluster, String keyspace, Class<DATA> dataClass, String table, ConsistencyLevel read, ConsistencyLevel write) {
		super(trans, name, cluster,keyspace,dataClass);
		this.table = table;
		readConsistency = read;
		writeConsistency = write;
	}
	
	/**
	 * A Constructor to share Session with other DAOs.
	 *
	 * This method get the Session and Cluster information from the calling DAO, and won't
	 * touch the Session on closure.
	 *
	 * @param aDao
	 * @param dataClass
	 */
	public CassDAOImpl(TRANS trans, String name, AbsCassDAO<TRANS,?> aDao, Class<DATA> dataClass, String table, ConsistencyLevel read, ConsistencyLevel write) {
		super(trans, name, aDao,dataClass);
		this.table = table;
		readConsistency = read;
		writeConsistency = write;
	}

	protected PSInfo createPS;
	protected PSInfo readPS;
	protected PSInfo updatePS;
	protected PSInfo deletePS;
	private boolean async=false;

	public void async(boolean bool) {
		async = bool;
	}

	public final String[] setCRUD(TRANS trans, String table, Class<?> dc,Loader<DATA> loader) {
		return setCRUD(trans, table, dc, loader, -1);
	}
	
	public final String[] setCRUD(TRANS trans, String table, Class<?> dc,Loader<DATA> loader, int max) {
				Field[] fields = dc.getDeclaredFields();
				int end = max>=0 & max<fields.length?max:fields.length;
				// get keylimit from a non-null Loader
				int keylimit = loader.keylimit();
			
				StringBuilder sbfc = new StringBuilder();
				StringBuilder sbq = new StringBuilder();
				StringBuilder sbwc = new StringBuilder();
				StringBuilder sbup = new StringBuilder();
			
				if(keylimit>0) {
					for(int i=0;i<end;++i) {
						if(i>0) {
							sbfc.append(',');
							sbq.append(',');
							if(i<keylimit) {
								sbwc.append(" AND ");
							}
						}
						sbfc.append(fields[i].getName());
						sbq.append('?');
						if(i>=keylimit) {
							if(i>keylimit) {
								sbup.append(',');
							}
							sbup.append(fields[i].getName());
							sbup.append("=?");
						}
						if(i<keylimit) {
							sbwc.append(fields[i].getName());
							sbwc.append("=?");
						}
					}
			
					createPS = new PSInfo(trans, "INSERT INTO " + table + " ("+ sbfc +") VALUES ("+ sbq +");",loader,writeConsistency);
			
					readPS = new PSInfo(trans, "SELECT " + sbfc + " FROM " + table + " WHERE " + sbwc + ';',loader,readConsistency);
			
					// Note: UPDATES can't compile if there are no fields besides keys... Use "Insert"
					if(sbup.length()==0) {
						updatePS = createPS; // the same as an insert
					} else {
						updatePS = new PSInfo(trans, "UPDATE " + table + " SET " + sbup + " WHERE " + sbwc + ';',loader,writeConsistency);
					}
			
					deletePS = new PSInfo(trans, "DELETE FROM " + table + " WHERE " + sbwc + ';',loader,writeConsistency);
				}
				return new String[] {sbfc.toString(), sbq.toString(), sbup.toString(), sbwc.toString()};
			}

	public void replace(CRUD crud, PSInfo psInfo) {
		switch(crud) {
			case create: createPS = psInfo; break;
			case read:   readPS = psInfo; break;
			case update: updatePS = psInfo; break;
			case delete: deletePS = psInfo; break;
		}
	}

	public void disable(CRUD crud) {
		switch(crud) {
			case create: createPS = null; break;
			case read:   readPS = null; break;
			case update: updatePS = null; break;
			case delete: deletePS = null; break;
		}
	}

	
	/**
	 * Given a DATA object, extract the individual elements from the Data into an Object Array for the
	 * execute element.
	 */
	public Result<DATA> create(TRANS trans, DATA data)  {
		if(createPS==null) {
			Result.err(Result.ERR_NotImplemented,"Create is disabled for %s",getClass().getSimpleName());
		}
		if(async) /*ResultSetFuture */ {
			Result<ResultSetFuture> rs = createPS.execAsync(trans, C_TEXT, data);
			if(rs.notOK()) {
				return Result.err(rs);
			}
		} else {
			Result<ResultSet> rs = createPS.exec(trans, C_TEXT, data);
			if(rs.notOK()) {
				return Result.err(rs);
			}
		}
		wasModified(trans, CRUD.create, data);
		return Result.ok(data);
	}

	/**
	 * Read the Unique Row associated with Full Keys
	 */
	public Result<List<DATA>> read(TRANS trans, DATA data) {
		if(readPS==null) {
			Result.err(Result.ERR_NotImplemented,"Read is disabled for %s",getClass().getSimpleName());
		}
		return readPS.read(trans, R_TEXT, data);
	}

	public Result<List<DATA>> read(TRANS trans, Object ... key) {
		if(readPS==null) {
			Result.err(Result.ERR_NotImplemented,"Read is disabled for %s",getClass().getSimpleName());
		}
		return readPS.read(trans, R_TEXT, key);
	}

	public Result<Void> update(TRANS trans, DATA data) {
		if(updatePS==null) {
			Result.err(Result.ERR_NotImplemented,"Update is disabled for %s",getClass().getSimpleName());
		}
		if(async)/* ResultSet rs =*/ {
			Result<ResultSetFuture> rs = updatePS.execAsync(trans, U_TEXT, data);
			if(rs.notOK()) {
				return Result.err(rs);
			}
		} else {
			Result<ResultSet> rs = updatePS.exec(trans, U_TEXT, data);
			if(rs.notOK()) {
				return Result.err(rs);
			}
		}
		
		wasModified(trans, CRUD.update, data);
		return Result.ok();
	}

	// This method Sig for Cached...
	public Result<Void> delete(TRANS trans, DATA data, boolean reread) {
		if(deletePS==null) {
			Result.err(Result.ERR_NotImplemented,"Delete is disabled for %s",getClass().getSimpleName());
		}
		// Since Deleting will be stored off, for possible re-constitution, need the whole thing
		if(reread) {
			Result<List<DATA>> rd = read(trans,data);
			if(rd.notOK()) {
				return Result.err(rd);
			}
			if(rd.isEmpty()) {
				return Result.err(Status.ERR_NotFound,"Not Found");
			}
			for(DATA d : rd.value) { 
				if(async) {
					Result<ResultSetFuture> rs = deletePS.execAsync(trans, D_TEXT, d);
					if(rs.notOK()) {
						return Result.err(rs);
					}
				} else {
					Result<ResultSet> rs = deletePS.exec(trans, D_TEXT, d);
					if(rs.notOK()) {
						return Result.err(rs);
					}
				}
				wasModified(trans, CRUD.delete, d);
			}
		} else {
			if(async)/* ResultSet rs =*/ {
				Result<ResultSetFuture> rs = deletePS.execAsync(trans, D_TEXT, data);
				if(rs.notOK()) {
					return Result.err(rs);
				}
			} else {
				Result<ResultSet> rs = deletePS.exec(trans, D_TEXT, data);
				if(rs.notOK()) {
					return Result.err(rs);
				}
			}
			wasModified(trans, CRUD.delete, data);
		}
		return Result.ok();
	}
	
	public final Object[] keyFrom(DATA data) {
		return createPS.keyFrom(data);
	}

	@Override
	public String table() {
		return table;
	}
	
	public static final String CASS_READ_CONSISTENCY="cassandra.readConsistency";
	public static final String CASS_WRITE_CONSISTENCY="cassandra.writeConsistency";
	protected static ConsistencyLevel readConsistency(AuthzTrans trans, String table) {
		String prop = trans.getProperty(CASS_READ_CONSISTENCY+'.'+table);
		if(prop==null) {
			prop = trans.getProperty(CASS_READ_CONSISTENCY);
			if(prop==null) {
				return ConsistencyLevel.ONE; // this is Cassandra Default
			}
		}
		return ConsistencyLevel.valueOf(prop);
	}

	protected static ConsistencyLevel writeConsistency(AuthzTrans trans, String table) {
		String prop = trans.getProperty(CASS_WRITE_CONSISTENCY+'.'+table);
		if(prop==null) {
			prop = trans.getProperty(CASS_WRITE_CONSISTENCY);
			if(prop==null) {
				return ConsistencyLevel.ONE; // this is Cassandra Default\
			}
		}
		return ConsistencyLevel.valueOf(prop);
	}

	public static DataInputStream toDIS(ByteBuffer bb) {
		byte[] b = bb.array();
		return new DataInputStream(
			new ByteArrayInputStream(b,bb.position(),bb.limit())
		);
	}


}
