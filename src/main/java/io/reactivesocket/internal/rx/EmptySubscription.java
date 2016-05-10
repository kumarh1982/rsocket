/**
 * Copyright 2015 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package io.reactivesocket.internal.rx;

import org.reactivestreams.*;

/**
 * An empty subscription that does nothing other than validates the request amount.
 */
public enum EmptySubscription implements Subscription {
    /** A singleton, stateless instance. */
    INSTANCE;
    
    @Override
    public void request(long n) {
        SubscriptionHelper.validateRequest(n);
    }
    @Override
    public void cancel() {
        // no-op
    }
    
    @Override
    public String toString() {
        return "EmptySubscription";
    }
    
    /**
     * Sets the empty subscription instance on the subscriber and then
     * calls onError with the supplied error.
     * 
     * <p>Make sure this is only called if the subscriber hasn't received a 
     * subscription already (there is no way of telling this).
     * 
     * @param e the error to deliver to the subscriber
     * @param s the target subscriber
     */
    public static void error(Throwable e, Subscriber<?> s) {
        s.onSubscribe(INSTANCE);
        s.onError(e);
    }

    /**
     * Sets the empty subscription instance on the subscriber and then
     * calls onComplete.
     * 
     * <p>Make sure this is only called if the subscriber hasn't received a 
     * subscription already (there is no way of telling this).
     * 
     * @param s the target subscriber
     */
    public static void complete(Subscriber<?> s) {
        s.onSubscribe(INSTANCE);
        s.onComplete();
    }
}