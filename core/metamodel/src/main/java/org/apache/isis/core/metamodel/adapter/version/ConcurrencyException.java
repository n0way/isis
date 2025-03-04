/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.isis.core.metamodel.adapter.version;

import org.apache.isis.core.commons.exceptions.IsisException;
import org.apache.isis.core.metamodel.adapter.mgr.AdapterManager.ConcurrencyChecking;
import org.apache.isis.core.metamodel.adapter.oid.Oid;
import org.apache.isis.core.metamodel.adapter.oid.OidMarshaller;

public class ConcurrencyException extends IsisException {
    
    private static final long serialVersionUID = 1L;

    /**
     * Provides a mechanism to temporarily disable concurrency checking.
     * 
     * <p>
     * This thread-local is not used by this class, but is defined here as a central point for other methods
     * to read/write.  The idea is that if concurrency checking is to be temporarily disabled, then the caller can
     * set this threadlocal to {@link ConcurrencyChecking#NO_CHECK no-check}, and then the code that would normally
     * detect the concurrency problem and throw this exception would instead consult this thread local and suppress
     * the exception being raised.

     * <p>
     * In this design, it is the responsibility of the caller that is disabling concurrency exception handling to reinstate it
     * afterwards.  This should normally be done using a try...finally.
     */
    public static ThreadLocal<ConcurrencyChecking> concurrencyChecking = new ThreadLocal<ConcurrencyChecking>(){
        protected ConcurrencyChecking initialValue() {
            return ConcurrencyChecking.CHECK;
        };
    };
    
    private static String buildMessage(String currentUser, Oid oid, Version staleVersion, Version datastoreVersion) {
        
        final StringBuilder buf = new StringBuilder();
        buf.append(currentUser != null? currentUser + " " : "");
        buf.append(" attempted to update ").append(oid.enStringNoVersion(getOidMarshaller()));
        buf.append(", however this object has since been modified");
        if(datastoreVersion.getUser() != null) {
            buf.append(" by ").append(datastoreVersion.getUser());
        }
        if(datastoreVersion.getTime() != null) {
            buf.append(" at ").append(datastoreVersion.getTime());
        }
        buf.append(" [").append(staleVersion.getSequence()).append(" vs ").append(datastoreVersion.getSequence()).append("]");
        
        return buf.toString();
    }

    private final Oid oid;

    public ConcurrencyException(final String currentUser, final Oid oid, final Version staleVersion, final Version datastoreVersion) {
        this(buildMessage(currentUser, oid, staleVersion, datastoreVersion), oid);
    }

    public ConcurrencyException(final String message, final Oid oid) {
        super(message);
        this.oid = oid;
    }

    public Oid getOid() {
        return oid;
    }
    
    private static OidMarshaller getOidMarshaller() {
        return new OidMarshaller();
    }

}
