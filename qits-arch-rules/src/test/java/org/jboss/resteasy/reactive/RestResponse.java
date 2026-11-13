package org.jboss.resteasy.reactive;

/**
 * A TEST FIXTURE mirroring RESTEasy Reactive's response wrapper by fully-qualified name, which
 * {@code EntityExposure} and {@code OfflineCapableRules} unwrap by name: this module depends on no
 * RESTEasy Reactive, so the offline fixtures compile against this instead.
 */
public abstract class RestResponse<T> {

  public abstract T getEntity();
}
