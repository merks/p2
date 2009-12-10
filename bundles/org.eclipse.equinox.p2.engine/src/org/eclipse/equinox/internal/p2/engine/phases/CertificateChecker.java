/*******************************************************************************
 * Copyright (c) 2008, 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.internal.p2.engine.phases;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Iterator;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.equinox.internal.p2.engine.EngineActivator;
import org.eclipse.equinox.internal.p2.engine.Messages;
import org.eclipse.equinox.internal.provisional.p2.core.IServiceUI;
import org.eclipse.equinox.internal.provisional.p2.core.IServiceUI.TrustInfo;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.osgi.service.security.TrustEngine;
import org.eclipse.osgi.signedcontent.*;
import org.eclipse.osgi.util.NLS;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

public class CertificateChecker {
	private ArrayList artifacts;
	private final IProvisioningAgent agent;

	public CertificateChecker() {
		this(null);
	}

	public CertificateChecker(IProvisioningAgent agent) {
		this.agent = agent;
		artifacts = new ArrayList();
	}

	public IStatus start() {
		final BundleContext context = EngineActivator.getContext();
		ServiceReference contentFactoryRef = context.getServiceReference(SignedContentFactory.class.getName());
		SignedContentFactory verifierFactory = (SignedContentFactory) context.getService(contentFactoryRef);
		ServiceReference trustEngineRef = context.getServiceReference(TrustEngine.class.getName());
		TrustEngine trustEngine = (TrustEngine) context.getService(trustEngineRef);
		try {
			return checkCertificates(trustEngine, verifierFactory);
		} finally {
			context.ungetService(contentFactoryRef);
			context.ungetService(trustEngineRef);
		}
	}

	private IStatus checkCertificates(TrustEngine trustEngine, SignedContentFactory verifierFactory) {
		IServiceUI serviceUI = (IServiceUI) agent.getService(IServiceUI.SERVICE_NAME);
		SignedContent content = null;
		SignerInfo[] signerInfo = null;
		ArrayList untrusted = new ArrayList();
		ArrayList unsigned = new ArrayList();
		ArrayList untrustedChain = new ArrayList();
		IStatus status = Status.OK_STATUS;
		if (artifacts.size() == 0 || serviceUI == null)
			return status;
		for (Iterator it = artifacts.iterator(); it.hasNext();) {
			File artifact = (File) it.next();
			try {
				content = verifierFactory.getSignedContent(artifact);
				if (!content.isSigned()) {
					unsigned.add(artifact);
					continue;
				}
				signerInfo = content.getSignerInfos();
			} catch (GeneralSecurityException e) {
				return new Status(IStatus.ERROR, EngineActivator.ID, Messages.CertificateChecker_SignedContentError, e);
			} catch (IOException e) {
				return new Status(IStatus.ERROR, EngineActivator.ID, Messages.CertificateChecker_SignedContentIOError, e);
			}
			for (int i = 0; i < signerInfo.length; i++) {
				Certificate[] certificateChain = signerInfo[i].getCertificateChain();
				try {
					Certificate trustAnchor = trustEngine.findTrustAnchor(certificateChain);
					if (trustAnchor == null) {
						if (!untrusted.contains(certificateChain[0])) {
							untrusted.add(certificateChain[0]);
							untrustedChain.add(certificateChain);
						}
					}
				} catch (IOException e) {
					return new Status(IStatus.ERROR, EngineActivator.ID, Messages.CertificateChecker_KeystoreConnectionError, e);
				}
			}
		}
		String policy = getUnsignedContentPolicy();
		//if there is unsigned content and we should never allow it, then fail without further checking certificates
		if (!unsigned.isEmpty() && EngineActivator.UNSIGNED_FAIL.equals(policy))
			return new Status(IStatus.ERROR, EngineActivator.ID, NLS.bind(Messages.CertificateChecker_UnsignedNotAllowed, unsigned));

		String[] details;
		// If we always allow unsigned content, or we don't have any, we don't prompt the user about it
		if (EngineActivator.UNSIGNED_ALLOW.equals(policy) || unsigned.isEmpty())
			details = null;
		else {
			details = new String[unsigned.size()];
			for (int i = 0; i < details.length; i++) {
				details[i] = unsigned.get(i).toString();
			}
		}
		Certificate[][] unTrustedCertificateChains;
		if (untrusted.isEmpty()) {
			unTrustedCertificateChains = null;
		} else {
			unTrustedCertificateChains = new Certificate[untrustedChain.size()][];
			for (int i = 0; i < untrustedChain.size(); i++) {
				unTrustedCertificateChains[i] = (Certificate[]) untrustedChain.get(i);
			}
		}

		// If there was no unsigned content, and nothing untrusted, no need to prompt.
		if (details == null && unTrustedCertificateChains == null)
			return status;

		TrustInfo trustInfo = serviceUI.getTrustInfo(unTrustedCertificateChains, details);

		// If user doesn't trust unsigned content, cancel the operation
		if (!trustInfo.trustUnsignedContent())
			return Status.CANCEL_STATUS;

		Certificate[] trustedCertificates = trustInfo.getTrustedCertificates();
		// If we had untrusted chains and nothing was trusted, cancel the operation
		if (unTrustedCertificateChains != null && trustedCertificates == null) {
			return new Status(IStatus.CANCEL, EngineActivator.ID, Messages.CertificateChecker_CertificateRejected);
		}
		// Anything that was trusted should be removed from the untrusted list
		if (trustedCertificates != null) {
			for (int i = 0; i < trustedCertificates.length; i++) {
				untrusted.remove(trustedCertificates[i]);
			}
		}

		// If there is still untrusted content, cancel the operation
		if (untrusted.size() > 0)
			return new Status(IStatus.CANCEL, EngineActivator.ID, Messages.CertificateChecker_CertificateRejected);
		// If we should persist the trusted certificates, add them to the trust engine
		if (trustInfo.persistTrust()) {
			for (int i = 0; i < trustedCertificates.length; i++) {
				try {
					trustEngine.addTrustAnchor(trustedCertificates[i], trustedCertificates[i].toString());
				} catch (IOException e) {
					//just return an INFO so the user can proceed with the install
					return new Status(IStatus.INFO, EngineActivator.ID, Messages.CertificateChecker_KeystoreConnectionError, e);
				} catch (GeneralSecurityException e) {
					return new Status(IStatus.INFO, EngineActivator.ID, Messages.CertificateChecker_CertificateError, e);
				}
			}
		}

		return status;
	}

	/**
	 * Return the policy on unsigned content.
	 */
	private String getUnsignedContentPolicy() {
		String policy = EngineActivator.getContext().getProperty(EngineActivator.PROP_UNSIGNED_POLICY);
		if (policy == null)
			policy = EngineActivator.UNSIGNED_PROMPT;
		return policy;

	}

	public void add(File toAdd) {
		artifacts.add(toAdd);
	}

	public void add(Object[] toAdd) {
		for (int i = 0; i < toAdd.length; i++) {
			if (toAdd[i] instanceof File)
				add((File) toAdd[i]);
		}
	}
}