/****************************************************************************
 * Copyright (c) 2024 Composent, Inc. and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * Contributors: Composent, Inc. - initial API and implementation
 *
 * SPDX-License-Identifier: EPL-2.0
 *****************************************************************************/
package org.eclipse.ecf.core.security;

import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import javax.net.ssl.SSLContext;

/**
 * @since 3.12
 */
public interface SSLContextFactory {

	SSLContext getDefault() throws NoSuchAlgorithmException, NoSuchProviderException;

	SSLContext getInstance(String protocol) throws NoSuchAlgorithmException, NoSuchProviderException;

	SSLContext getInstance(String protocol, String providerName) throws NoSuchAlgorithmException, NoSuchProviderException;

}
