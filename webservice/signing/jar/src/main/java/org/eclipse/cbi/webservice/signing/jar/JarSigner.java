/*******************************************************************************
 * Copyright (c) 2015 Eclipse Foundation and others
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Mikaël Barbero - initial implementation
 *******************************************************************************/
package org.eclipse.cbi.webservice.signing.jar;

import static com.google.common.base.Preconditions.checkState;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import javax.annotation.Nullable;

import org.eclipse.cbi.util.ProcessExecutor;

import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

/**
 * Abstraction of a call to jarsigner command.
 */
@AutoValue
public abstract class JarSigner {

	/**
	 * Returns the path to the jar signer binary
	 * 
	 * @return the path to the jar signer binary
	 */
	abstract Path jarSigner();

	/**
	 * Returns the path to the keystore file
	 * 
	 * @return the path to the keystore file
	 */
	abstract Path keystore();

	/**
	 * Returns the path to the file storing the keystore password
	 * 
	 * @return the path to the file storing the keystore password
	 */
	abstract String keystorePassword();

	/**
	 * Returns the alias name of the key in the keystore
	 * 
	 * @return the alias name of the key in the keystore
	 */
	abstract String keystoreAlias();

	/**
	 * Returns the timestamping authority URI
	 * 
	 * @return the timestamping authority URI
	 */
	abstract URI timestampingAuthority();

	abstract String httpProxyHost();
	abstract int httpProxyPort();
	abstract String httpsProxyHost();
	abstract int httpsProxyPort();
	
	/**
	 * Returns the digest algorithm to be used by the {@link #jarSigner()}.
	 * @return the digest algorithm to be used by the {@link #jarSigner()}.
	 */
	@Nullable
	abstract MessageDigestAlgorithm digestAlgorithm();
	
	/**
	 * Returns the executor that will execute the native command
	 * 
	 * @return the executor that will execute the native command
	 */
	abstract ProcessExecutor processExecutor();

	/**
	 * Returns the timeout of the jarsigner command
	 * 
	 * @return the timeout of the jarsigner command
	 */
	abstract long timeout();
	
	/**
	 * Creates and returns a new builder for this class.
	 * 
	 * @return a new builder for this class.
	 */
	public static Builder builder() {
		return new AutoValue_JarSigner.Builder()
			.httpProxyHost("")
			.httpProxyPort(0)
			.httpsProxyHost("")
			.httpsProxyPort(0)
			.digestAlgorithm(null);
	}
	
	/**
	 * A builder of JarSigner.
	 */
	@AutoValue.Builder
	public static abstract class Builder {
	
		/**
		 * Sets the path to the jarsigner command.
		 * 
		 * @return this builder for daisy-chaining.
		 */
		public abstract Builder jarSigner(Path jarSigner);
		
		/**
		 * Sets the path to the keystore file.
		 * 
		 * @return this builder for daisy-chaining.
		 */
		public abstract Builder keystore(Path keystore);
		
		/**
		 * Sets the path to the file storing the password of the keystore.
		 * 
		 * @return this builder for daisy-chaining.
		 */
		public abstract Builder keystorePassword(String keystorePassword);
		
		/**
		 * Sets the alias name of the key in the keystore.
		 * 
		 * @return this builder for daisy-chaining.
		 */
		public abstract Builder keystoreAlias(String keystoreAlias);
		
		/**
		 * Sets the URI of the timestamping authority used by jarsigner.
		 * 
		 * @return this builder for daisy-chaining.
		 */
		public abstract Builder timestampingAuthority(URI timeStampingAuthority);
		
		/**
		 * Sets the process executor that will execute the native jarsigner command. 
		 * 
		 * @return this builder for daisy-chaining.
		 */
		public abstract Builder processExecutor(ProcessExecutor executor);
		
		/**
		 * Sets the timeout before which the jarsigner process will be killed.
		 * 
		 * @return this builder for daisy-chaining.
		 */
		public abstract Builder timeout(long timeout);
		
		public abstract Builder digestAlgorithm(@Nullable MessageDigestAlgorithm digestAlg);
		
		public abstract Builder httpProxyHost(String proxyHost);
		public abstract Builder httpProxyPort(int proxyPort);
		public abstract Builder httpsProxyHost(String proxyHost);
		public abstract Builder httpsProxyPort(int proxyPort);
		
		abstract JarSigner autoBuild();
		
		/**
		 * Creates and returns a new JarSigner object with the state of this
		 * builder.
		 * 
		 * @return a new JarSigner object with the state of this builder.
		 */
		public JarSigner build() {
			JarSigner jarSigner = autoBuild();
			checkState(jarSigner.timeout() > 0, "The timeout must be strictly positive");
			if (!Strings.isNullOrEmpty(jarSigner.httpProxyHost())) {
				checkState(jarSigner.httpProxyPort() > 0, "The HTTP proxy port must be specified and stricly positive when HTTP proxy host is");
			}
			if (!Strings.isNullOrEmpty(jarSigner.httpsProxyHost())) {
				checkState(jarSigner.httpsProxyPort() > 0, "The HTTPS proxy port must be specified and stricly positive when HTTPS proxy host is");
			}
			return jarSigner;
		}
	}

	/**
	 * Sign the given jar file with the configured jarsigner command.
	 * 
	 * @param jar
	 *            the jar to be sign
	 * @return the path to the signed jar file (the same as the one given in
	 *         parameter).
	 * @throws IOException
	 *             if the execution of the command did not end properly.
	 */
	public Path signJar(Path jar) throws IOException {
		return signJar(jar, MessageDigestAlgorithm.DEFAULT);
	}
	
	/**
	 * Sign the given jar file with the configured jarsigner command.
	 * 
	 * @param jar
	 *            the jar to be sign
	 * @param digestAlg
	 *            the message digest algorithm to use when digesting the entries
	 *            of a JAR file. If <code>null</code>, jarsigner will use its
	 *            default digest algorithm.
	 * @return the path to the signed jar file (the same as the one given in
	 *         parameter).
	 * @throws IOException
	 *             if the execution of the command did not end properly.
	 */
	public Path signJar(Path jar, MessageDigestAlgorithm digestAlg) throws IOException {
		return signJar(jar, digestAlg, ResigningStrategy.resign(this, MessageDigestAlgorithm.DEFAULT));
	}
	
	/**
	 * Sign the given jar file with the configured jarsigner command. If the jar
	 * is already signed, use the given {@link ResigningStrategy} to resign it.
	 * 
	 * @param jar
	 *            the jar to be sign
	 * @param digestAlg
	 *            the message digest algorithm to use when digesting the entries
	 *            of a JAR file. If <code>null</code>, jarsigner will use its
	 *            default digest algorithm.
	 * @param resigningStrategy
	 *            the strategy to be used if the given jar is already signed.
	 * @return the path to the signed jar file (the same as the one given in
	 *         parameter).
	 * @throws IOException
	 *             if the execution of the command did not end properly.
	 */
	public Path signJar(Path jar, MessageDigestAlgorithm digestAlg, ResigningStrategy resigningStrategy) throws IOException {
		final boolean alreadySigned = isAlreadySigned(jar);
		
		if (alreadySigned) {
			return resigningStrategy.resignJar(jar);
		} else {
			return doSign(jar, digestAlg);
		}
	}

	private static boolean isAlreadySigned(Path jar) throws IOException {
		final boolean alreadySigned;
		try (JarInputStream jis = new JarInputStream(Files.newInputStream(jar))) {
			JarEntry nextJarEntry = jis.getNextJarEntry();
			if (nextJarEntry != null) {
				CodeSigner[] codeSigners = nextJarEntry.getCodeSigners();
				if (codeSigners != null) {
					alreadySigned = codeSigners.length > 0;
				} else {
					alreadySigned = false;
				}
			} else {
				alreadySigned = false;
			}
		}
		return alreadySigned;
	}
	
	Path doSign(Path jar, MessageDigestAlgorithm digestAlg) throws IOException {
		final StringBuffer output = new StringBuffer();
		int jarSignerExitValue = processExecutor().exec(createCommand(jar, digestAlg), output , timeout(), TimeUnit.SECONDS);
		if (jarSignerExitValue != 0) {
			throw new IOException(Joiner.on('\n').join(
					"The '" + jarSigner().toString() + "' command exited with value '" + jarSignerExitValue + "'",
					"'" + jarSigner().toString() + "' output:",
					output));
		}
		return jar;
	}
	
	/**
	 * Creates the jarsigner command with proper options.
	 * 
	 * @param jar
	 *            the path of the file to be signed.
	 * @param digestAlg
	 *            the message digest algorithm to use when digesting the entries
	 *            of a JAR file. May be <code>null</code>.
	 * @return a list of string composing the command (see
	 *         {@link ProcessBuilder} for format).
	 */
	private ImmutableList<String> createCommand(Path jar, MessageDigestAlgorithm digestAlg) {
		ImmutableList.Builder<String> command = ImmutableList.<String>builder().add(jarSigner().toString());
		
		if (!Strings.isNullOrEmpty(httpProxyHost())) {
			command.add("-J-Dhttp.proxyHost=" + httpProxyHost()).add("-J-Dhttp.proxyPort=" + httpProxyPort());
		}
		
		if (!Strings.isNullOrEmpty(httpProxyHost())) {
			command.add("-J-Dhttps.proxyHost=" + httpsProxyHost()).add("-J-Dhttps.proxyPort=" + httpsProxyPort());
		}
		
		if (digestAlg != MessageDigestAlgorithm.DEFAULT) {
			command.add("-digestalg", digestAlg.standardName());
		}

		command.add("-tsa", timestampingAuthority().toString())
			.add("-verbose")
			.add("-keystore", keystore().toString())
			.add("-storepass", keystorePassword())
			.add(jar.toString())
			.add(keystoreAlias());
		
		return command.build();
	}
}