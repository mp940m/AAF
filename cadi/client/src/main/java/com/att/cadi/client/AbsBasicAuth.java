/*******************************************************************************
 * Copyright (c) 2016 AT&T Intellectual Property. All rights reserved.
 *******************************************************************************/
package com.att.cadi.client;

import java.io.IOException;

import com.att.cadi.SecuritySetter;
import com.att.cadi.Symm;
import com.att.cadi.config.SecurityInfoC;

public abstract class AbsBasicAuth<CLIENT> implements SecuritySetter<CLIENT> {
		protected static final String REPEAT_OFFENDER="This call is aborted because of repeated usage of invalid Passwords";
		private static final int MAX_TEMP_COUNT = 10;
		private static final int MAX_SPAM_COUNT = 10000;
		private static final long WAIT_TIME = 1000*60*4;
		
		protected final String headValue;
		protected SecurityInfoC<CLIENT> securityInfo;
		protected String user;
		private long lastMiss;
		private int count;

		public AbsBasicAuth(String user, String pass, SecurityInfoC<CLIENT> si) throws IOException {
			this.user = user;
			headValue = "Basic " + Symm.base64.encode(user + ':' + pass);
			securityInfo = si;
			lastMiss=0L;
			count=0;
		}

		/* (non-Javadoc)
		 * @see com.att.cadi.SecuritySetter#getID()
		 */
		@Override
		public String getID() {
			return user;
		}
		
		public boolean isDenied() {
			if(lastMiss>0 && lastMiss>System.currentTimeMillis()) {
				return true;
			} else {
				lastMiss=0L;
				return false;
			}
		}
		
		public synchronized int setLastResponse(int httpcode) {
			if(httpcode == 401) {
				++count;
				if(lastMiss==0L && count>MAX_TEMP_COUNT) {
					lastMiss=System.currentTimeMillis()+WAIT_TIME;
				}
//				if(count>MAX_SPAM_COUNT) {
//					System.err.printf("Your service has %d consecutive bad service logins to AAF. \nIt will now exit\n",
//							count);
//					System.exit(401);
//				}
				if(count%1000==0) {
					System.err.printf("Your service has %d consecutive bad service logins to AAF. AAF Access will be disabled after %d\n",
						count,MAX_SPAM_COUNT);
				}

			} else {
				lastMiss=0;
			}
			return count;
		}
		
		public int count() {
			return count;
		}
}
