/*******************************************************************************
 * Copyright (c) 2012, 2015 Eclipse Foundation and others. 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors: 
 *   Eclipse Foundation - initial API and implementation 
 *   Thanh Ha (Eclipse Foundation) - Add support for signing inner jars
 *   Mikael Barbero - Use of "try with resource"
 *******************************************************************************/
package org.eclipse.cbi.maven.plugins.jarsigner;

import java.io.File;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.cbi.common.FileProcessor;
import org.eclipse.cbi.common.http.ApacheHttpClientFileProcessor;
import org.eclipse.cbi.maven.common.MavenLogger;

/**
 * Signs project main and attached artifact using <a href=
 * "http://wiki.eclipse.org/IT_Infrastructure_Doc#Sign_my_plugins.2FZIP_files.3F">
 * Eclipse jarsigner webservice</a>. Only artifacts that have extension
 * {@code .jar} are signed, other artifacts are not signed with a debug log
 * message.
 */
@Mojo(name = "sign", defaultPhase = LifecyclePhase.PACKAGE)
public class SignMojo extends AbstractMojo {

	/**
	 * The name of the part as it will be send to the signing server.
	 */
	private static final String PART_NAME = "file";

	/**
	 * The default number of seconds the process will wait if 
	 */
	private static final String DEFAULT_RETRY_TIMER_STRING = "30";
	private static final int DEFAULT_RETRY_TIMER = Integer.parseInt(DEFAULT_RETRY_TIMER_STRING);

	private static final String DEFAULT_RETRY_LIMIT_STRING = "3";
	private static final int DEFAULT_RETRY_LIMIT = Integer.parseInt(DEFAULT_RETRY_LIMIT_STRING);

	/**
	 * The Maven project.
	 */
	@Parameter(defaultValue = "${project}", readonly = true)
	private MavenProject project;

	/**
	 * The Maven build directory.
	 *
	 * @deprecated not used anymore. All temporary file are created in the
	 *             parent folder of the artifact (i.e., the project build
	 *             directory).
	 */
	@Deprecated
	@Parameter(defaultValue = "${project.build.directory}", readonly = true)
	private File workdir;

	/**
	 * The signing service URL for signing Jar files. This service should return
	 * a signed jar file.
	 * 
	 * @since 1.0.4
	 */
	@Parameter(required = true, property = "cbi.jarsigner.signerUrl", defaultValue = "http://build.eclipse.org:31338/sign")
	private String signerUrl;

	/**
	 * Whether the execution of this plugin should be skipped.
	 *
	 * @since 1.0.4
	 */
	@Parameter(property = "cbi.jarsigner.skip", defaultValue = "false")
	private boolean skip;

	/**
	 * Whether the build should be stopped if the signing process fails.
	 *
	 * @since 1.0.5 (for the user property, since 1.2.0 for the parameter).
	 * @deprecated The user property {@code continueOnFail} is deprecated. You
	 *             should use the qualified property
	 *             {@code cbi.jarsigner.continueOnFail} instead. The
	 *             {@code deprecatedContinueOnFail} parameter has been
	 *             introduced to support this deprecated user property for
	 *             backward compatibility only.
	 */
	@Deprecated
	@Parameter(property = "continueOnFail", defaultValue = "false")
	private boolean deprecatedContinueOnFail;

	/**
	 * Whether the build should be stopped if the signing process fails.
	 *
	 * @since 1.0.5 (for the parameter, since 1.2.0 for the qualified user
	 *        property).
	 */
	@Parameter(property = "cbi.jarsigner.continueOnFail", defaultValue = "false")
	private boolean continueOnFail;

	/**
	 * Number of times to retry signing if the server fails to sign.
	 *
	 * @since 1.1.0 (for the property, since 1.2.0 for the parameter)
	 * @deprecated The user property {@code retryLimit} is deprecated. You
	 *             should use the qualified property
	 *             {@code cbi.jarsigner.retryLimit} instead. The
	 *             {@code deprecatedRetryLimit} parameter has been introduced to
	 *             support this deprecated user property for backward
	 *             compatibility only.
	 */
	@Deprecated
	@Parameter(property = "retryLimit", defaultValue = DEFAULT_RETRY_LIMIT_STRING)
	private int deprecatedRetryLimit;

	/**
	 * Number of times to retry signing if the server fails to sign.
	 * 
	 * @since 1.1.0 (for the parameter, since 1.2.0 for the qualified user user
	 *        property)
	 */
	@Parameter(property = "cbi.jarsigner.retryLimit", defaultValue = DEFAULT_RETRY_LIMIT_STRING)
	private int retryLimit;

	/**
	 * Number of seconds to wait before retrying to sign.
	 *
	 * @since 1.1.0 (for the user property, since 1.2.0 for the parameter).
	 * @deprecated The user property {@code retryTimer} is deprecated. You
	 *             should use the qualified property
	 *             {@code cbi.jarsigner.retryTimer} instead. The
	 *             {@code deprecatedRetryTimer} parameter has been introduced to
	 *             support this deprecated user property for backward
	 *             compatibility only.
	 */
	@Deprecated
	@Parameter(property = "retryTimer", defaultValue = DEFAULT_RETRY_TIMER_STRING)
	private int deprecatedRetryTimer;

	/**
	 * Number of seconds to wait before retrying to sign.
	 * 
	 * @since 1.1.0 (for the parameter, since 1.2.0 for the qualified user user
	 *        property)
	 */
	@Parameter(property = "cbi.jarsigner.retryTimer", defaultValue = DEFAULT_RETRY_TIMER_STRING)
	private int retryTimer;

	/**
	 * Whether to excludes signing inner jars (not recursive, only apply to
	 * first level Jars inside the build Jar file; deepest are ignored in all
	 * cases).
	 *
	 * @since 1.0.5
	 */
	@Parameter(defaultValue = "false")
	private boolean excludeInnerJars;

	/**
	 * Project types which this plugin supports.
	 * 
	 * @deprecated Not used anymore.
	 */
	@Deprecated
	@Parameter
	private List<String> supportedProjectTypes = Arrays.asList("jar", "war", "bundle", "maven-plugin", "eclipse-plugin",
			"eclipse-test-plugin", "eclipse-feature");

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void execute() throws MojoExecutionException {
		if (skip) {
			getLog().info("Skipping Jar signing");
			return;
		}

		final JarSigner jarSigner = createJarSigner();

		final Artifact mainArtifact = project.getArtifact();
		if (mainArtifact != null) {
			signArtifact(jarSigner, mainArtifact);
		}

		for (Artifact artifact : project.getAttachedArtifacts()) {
			signArtifact(jarSigner, artifact);
		}
	}

	private void signArtifact(final JarSigner jarSigner, final Artifact artifact) throws MojoExecutionException {
		File artifactFile = artifact.getFile();
		if (artifactFile != null) {
			jarSigner.signJar(artifactFile.toPath());
		} else {
			getLog().warn("Can't find associated file with artifact '" + artifact.toString() + "'");
		}
	}

	/**
	 * Creates and returns the {@link JarSigner} according to the injected Mojo
	 * parameter.
	 * 
	 * @return the {@link JarSigner} according to the injected Mojo parameter.
	 */
	private JarSigner createJarSigner() {
		URI signerURI = URI.create(signerUrl);
		final FileProcessor signer = new ApacheHttpClientFileProcessor(signerURI, PART_NAME, new MavenLogger(getLog()));
		JarSigner.Builder jarSignerBuilder = JarSigner.builder(signer);
		jarSignerBuilder.logOn(getLog());

		if (deprecatedRetryLimit != DEFAULT_RETRY_LIMIT && retryLimit == DEFAULT_RETRY_LIMIT) {
			jarSignerBuilder.maxRetry(deprecatedRetryLimit);
		} else {
			jarSignerBuilder.maxRetry(retryLimit);
		}

		if (deprecatedRetryTimer != DEFAULT_RETRY_TIMER && retryTimer == DEFAULT_RETRY_TIMER) {
			jarSignerBuilder.waitBeforeRetry(deprecatedRetryTimer, TimeUnit.SECONDS);
		} else {
			jarSignerBuilder.waitBeforeRetry(retryTimer, TimeUnit.SECONDS);
		}

		if (deprecatedContinueOnFail || continueOnFail) {
			jarSignerBuilder.continueOnFail();
		}

		if (excludeInnerJars) {
			jarSignerBuilder.maxDepth(0);
		} else {
			jarSignerBuilder.maxDepth(1);
		}

		return jarSignerBuilder.build();
	}
}