/*******************************************************************************
 * Copyright (c) 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.osgi.framework.adaptor.core;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.*;
import org.eclipse.osgi.framework.adaptor.BundleData;
import org.eclipse.osgi.framework.adaptor.ClassLoaderDelegate;
import org.eclipse.osgi.framework.debug.Debug;
import org.eclipse.osgi.framework.internal.core.Constants;
import org.eclipse.osgi.framework.internal.protocol.bundleentry.Handler;
import org.eclipse.osgi.framework.util.Headers;
import org.eclipse.osgi.util.ManifestElement;
import org.osgi.framework.*;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;

/**
 * An abstract BundleData class that has default implementations that most
 * BundleData implementations can use.
 */
public abstract class AbstractBundleData implements BundleData, Cloneable {

	/** the DefaultAdaptor for this BundleData */
	protected AbstractFrameworkAdaptor adaptor;

	/**
	 * The BundleManfifest for this BundleData.
	 */
	protected Dictionary manifest = null;

	/**
	 * The Bundle object for this BundleData.
	 */
	protected Bundle bundle;

	/** bundle id */
	protected long id;

	/** The top level storage directory for the BundleData */
	protected File bundleStoreDir;

	/** The base BundleFile object for this BundleData */
	protected BundleFile baseBundleFile;
	///////////////////// Begin Meta Data for the Bundle /////////////////////

	/** bundle location */
	private String location;

	/** bundle's file name */
	private String fileName;

	/** native code paths for this BundleData */
	private String[] nativePaths;

	/** bundle generation */
	private int generation = 1;

	/** the bundles start level */
	private int startLevel = -1;

	/**
	 * The BundleData data directory 
	 */
	protected File dirData;

	/** the bundles status */
	private int status = 0;

	/** Is bundle a reference */
	private boolean reference;

	/** the bundles last modified timestamp */
	private long lastModified;

	///////////////////// End Meta Data for the Bundle   /////////////////////

	///////////////////// Begin values from Manifest     /////////////////////
	private String symbolicName;
	private Version version;
	private String activator;
	private String classpath;
	private String executionEnvironment;
	private String dynamicImports;
	private boolean fragment = false;

	///////////////////// End values from Manifest       /////////////////////

	public AbstractBundleData(AbstractFrameworkAdaptor adaptor, long id) {
		this.adaptor = adaptor;
		this.id = id;
		initBundleStoreDirs(String.valueOf(id));
	}

	/**
	 * Return the BundleManifest for the BundleData.  If the manifest
	 * field is null this method will parse the bundle manifest file and
	 * construct a BundleManifest file to return.  If the manifest field is
	 * not null then the manifest object is returned.
	 * @return BundleManifest for the BundleData.
	 * @throws BundleException if an error occurred while reading the
	 * bundle manifest data.
	 */
	public Dictionary getManifest() throws BundleException {
		if (manifest == null) {
			synchronized (this) {
				// make sure the manifest is still null after we have aquired the lock.
				if (manifest == null) {
					URL url = getEntry(Constants.OSGI_BUNDLE_MANIFEST);
					if (url == null) {
						throw new BundleException(AdaptorMsg.formatter.getString("MANIFEST_NOT_FOUND_EXCEPTION", Constants.OSGI_BUNDLE_MANIFEST, getLocation())); //$NON-NLS-1$
					}
					try {
						manifest = Headers.parseManifest(url.openStream());
					} catch (IOException e) {
						throw new BundleException(AdaptorMsg.formatter.getString("MANIFEST_NOT_FOUND_EXCEPTION", Constants.OSGI_BUNDLE_MANIFEST, getLocation()), e); //$NON-NLS-1$
					}
				}
			}
		}
		return manifest;
	}

	/**
	 * Sets the Bundle object for this BundleData.
	 * @param bundle The Bundle Object for this BundleData.
	 */
	public void setBundle(Bundle bundle) {
		this.bundle = bundle;
	}

	/**
	 * Returns the Bundle object for this BundleData.
	 * @return the Bundle object for this BundleData.
	 */
	public Bundle getBundle() {
		return bundle;
	}

	/**
	 * Get the BundleData bundle ID.  This will be used as the bundle
	 * ID by the framework.
	 * @return The BundleData ID.
	 */
	public long getBundleID() {
		return (id);
	}

	/**
	 * Gets a <code>URL</code> to the bundle entry specified by path. This
	 * method must not use the BundleClassLoader to find the bundle entry since
	 * the ClassLoader will delegate to find the resource.
	 * 
	 * @param path
	 *            The bundle entry path.
	 * @return A URL used to access the entry or null if the entry does not
	 *         exist.
	 */
	public URL getEntry(String path) {
		BundleEntry entry = getBaseBundleFile().getEntry(path);
		if (entry == null) {
			return null;
		}
		try {
			return new URL(null, getBundleEntryURL(id, path), new Handler(entry));
		} catch (MalformedURLException e) {
			return null;
		}
	}

	/**
	 * Gets all of the bundle entries that exist under the specified path. For
	 * example:
	 * <p>
	 * <code>getEntryPaths("/META-INF")</code>
	 * <p>
	 * This will return all entries from the /META-INF directory of the bundle.
	 * 
	 * @param path
	 *            The path to a directory in the bundle.
	 * @return An Enumeration of the entry paths or null if the specified path
	 *         does not exist.
	 */
	public Enumeration getEntryPaths(String path) {
		return getBaseBundleFile().getEntryPaths(path);
	}

	/**
	 * Creates the ClassLoader for the BundleData.  The ClassLoader created
	 * must use the <code>ClassLoaderDelegate</code> to delegate class, resource
	 * and library loading.  The delegate is responsible for finding any resource
	 * or classes imported by the bundle or provided by bundle fragments or 
	 * bundle hosts.  The <code>ProtectionDomain</code> domain must be used
	 * by the Classloader when defining a class.  
	 * @param delegate The <code>ClassLoaderDelegate</code> to delegate to.
	 * @param domain The <code>ProtectionDomain</code> to use when defining a class.
	 * @param bundleclasspath An array of bundle classpaths to use to create this
	 * classloader.  This is specified by the Bundle-ClassPath manifest entry.
	 * @return The new ClassLoader for the BundleData.
	 */
	public org.eclipse.osgi.framework.adaptor.BundleClassLoader createClassLoader(ClassLoaderDelegate delegate, ProtectionDomain domain, String[] bundleclasspath) {
		return getAdaptor().getElementFactory().createClassLoader(delegate, domain, bundleclasspath, this);
	}

	public AbstractFrameworkAdaptor getAdaptor() {
		return adaptor;
	}

	public static String getBundleEntryURL(long id, String path) {
		StringBuffer url = new StringBuffer(Constants.OSGI_ENTRY_URL_PROTOCOL);
		url.append("://").append(id); //$NON-NLS-1$
		if (path.length() == 0 || path.charAt(0) != '/') {
			url.append('/');
		}
		url.append(path);
		return url.toString();
	}

	/* 
	 * Convenience method that retrieves the simbolic name string from the header.
	 * Note: clients may want to cache the returned value.
	 */
	public static String parseSymbolicName(Dictionary manifest) {
		String symbolicNameEntry = (String) manifest.get(Constants.BUNDLE_SYMBOLICNAME);
		if (symbolicNameEntry == null)
			return null;
		try {
			return ManifestElement.parseHeader(Constants.BUNDLE_SYMBOLICNAME, symbolicNameEntry)[0].getValue();
		} catch (BundleException e) {
			// here is not the place to validate a manifest			
		}
		return null;
	}

	///////////////////// Begin Meta Data Accessor Methods ////////////////////
	public String getLocation() {
		return location;
	}

	public void setLocation(String location) {
		this.location = location;
	}

	public String getFileName() {
		return fileName;
	}

	public void setFileName(String fileName) {
		this.fileName = fileName;
	}

	public String[] getNativePaths() {
		return nativePaths;
	}

	public String getNativePathsString() {
		if (nativePaths == null || nativePaths.length == 0)
			return null;
		StringBuffer sb = new StringBuffer();
		for (int i = 0; i < nativePaths.length; i++) {
			sb.append(nativePaths[i]);
			if (i < nativePaths.length - 1)
				sb.append(',');
		}
		return sb.toString();
	}

	public void setNativePaths(String[] nativePaths) {
		this.nativePaths = nativePaths;
	}

	public void setNativePaths(String nativePaths) {
		if (nativePaths == null)
			return;
		ArrayList result = new ArrayList(5);
		StringTokenizer st = new StringTokenizer(nativePaths, ","); //$NON-NLS-1$
		while (st.hasMoreTokens()) {
			String path = st.nextToken();
			result.add(path);
		}
		setNativePaths((String[]) result.toArray(new String[result.size()]));
	}

	public int getGeneration() {
		return generation;
	}

	public void setGeneration(int generation) {
		this.generation = generation;
	}

	public long getLastModified() {
		return lastModified;
	}

	public void setLastModified(long lastModified) {
		this.lastModified = lastModified;
	}

	public int getStartLevel() {
		return startLevel;
	}

	public void setStartLevel(int startLevel) {
		this.startLevel = startLevel;
	}

	public int getStatus() {
		return status;
	}

	public void setStatus(int status) {
		this.status = status;
	}

	public boolean isReference() {
		return reference;
	}

	public void setReference(boolean reference) {
		this.reference = reference;
	}

	///////////////////// End Meta Data Accessor Methods   ////////////////////

	///////////////////// Begin Manifest Value Accessor Methods /////////////////////

	public String getSymbolicName() {
		return symbolicName;
	}

	public File getBundleStoreDir() {
		return bundleStoreDir;
	}

	public void setSymbolicName(String symbolicName) {
		this.symbolicName = symbolicName;
	}

	protected void loadFromManifest() throws IOException, BundleException {
		getManifest();

		if (manifest == null) {
			throw new IOException(AdaptorMsg.formatter.getString("ADAPTOR_ERROR_GETTING_MANIFEST", getLocation())); //$NON-NLS-1$
		}
		setVersion(Version.parseVersion((String) manifest.get(Constants.BUNDLE_VERSION)));
		setSymbolicName(AbstractBundleData.parseSymbolicName(manifest));
		setClassPath((String) manifest.get(Constants.BUNDLE_CLASSPATH));
		setActivator((String) manifest.get(Constants.BUNDLE_ACTIVATOR));
		String host = (String) manifest.get(Constants.FRAGMENT_HOST);
		setFragment(host != null);
		setExecutionEnvironment((String) manifest.get(Constants.BUNDLE_REQUIREDEXECUTIONENVIRONMENT));
		setDynamicImports((String) manifest.get(Constants.DYNAMICIMPORT_PACKAGE));
	}

	public Version getVersion() {
		return version;
	}

	public void setVersion(Version version) {
		this.version = version;
	}

	public String getActivator() {
		return activator;
	}

	protected File getDataDir() {
		return dirData;
	}

	protected void setBundleStoreDir(File bundleStoreDir) {
		this.bundleStoreDir = bundleStoreDir;
	}

	protected void initBundleStoreDirs(String bundleID) {
		setBundleStoreDir(new File(((AbstractFrameworkAdaptor) adaptor).getBundleStoreRootDir(), bundleID));
	}

	public void setActivator(String activator) {
		this.activator = activator;
	}

	public String getClassPath() {
		return classpath;
	}

	public void setClassPath(String classpath) {
		this.classpath = classpath;
	}

	public String getExecutionEnvironment() {
		return executionEnvironment;
	}

	public void setExecutionEnvironment(String executionEnvironment) {
		this.executionEnvironment = executionEnvironment;
	}

	public String getDynamicImports() {
		return dynamicImports;
	}

	public void setDynamicImports(String dynamicImports) {
		this.dynamicImports = dynamicImports;
	}

	public boolean isFragment() {
		return fragment;
	}

	public void setFragment(boolean fragment) {
		this.fragment = fragment;
	}

	///////////////////// End Manifest Value Accessor Methods  /////////////////////

	/**
	 * Return a copy of this object with the
	 * generation dependent fields updated to
	 * the next free generation level.
	 *
	 * @throws IOException If there are no more available generation levels.
	 */
	protected AbstractBundleData nextGeneration(String referenceFile) throws IOException {
		int nextGeneration = getGeneration();

		while (nextGeneration < Integer.MAX_VALUE) {
			nextGeneration++;

			File nextDirGeneration = new File(getBundleStoreDir(), String.valueOf(nextGeneration));

			if (nextDirGeneration.exists()) {
				continue;
			}

			AbstractBundleData next;
			try {
				next = (AbstractBundleData) clone();
			} catch (CloneNotSupportedException e) {
				// this shouldn't happen, since we are Cloneable
				throw new InternalError();
			}

			next.setGeneration(nextGeneration);

			if (referenceFile != null) {
				next.setReference(true);
				next.setFileName(referenceFile);
			} else {
				if (next.isReference()) {
					next.setReference(false);
					next.setFileName(((AbstractFrameworkAdaptor) adaptor).mapLocationToName(getLocation()));
				}
			}

			// null out the manifest to force it to be re-read.
			next.manifest = null;
			return (next);
		}

		throw new IOException(AdaptorMsg.formatter.getString("ADAPTOR_STORAGE_EXCEPTION")); //$NON-NLS-1$
	}

	public void initializeNewBundle() throws IOException, BundleException {
		createBaseBundleFile();

		loadFromManifest();
	}

	protected BundleFile createBaseBundleFile() throws IOException {
		baseBundleFile = getAdaptor().createBundleFile(getBaseFile(), this);
		return baseBundleFile;
	}

	/**
	 * Return the base File for the bundle.
	 * Attempt to create the bundle generation directory if it does not exist.
	 *
	 * @return the base File object for the bundle.
	 */
	protected File getBaseFile() {
		return isReference() ? new File(getFileName()) : new File(createGenerationDir(), getFileName());
	}

	protected void setDataDir(File dirData) {
		this.dirData = dirData;
	}

	/**
	 * Returns the absolute path name of a native library. The BundleData
	 * ClassLoader invokes this method to locate the native libraries that 
	 * belong to classes loaded from this BundleData. Returns 
	 * null if the library does not exist in this BundleData.
	 * @param libname The name of the library to find the absolute path to.
	 * @return The absolute path name of the native library or null if
	 * the library does not exist.
	 */
	public String findLibrary(String libname) {
		String mappedName = System.mapLibraryName(libname);
		String path = null;

		if (Debug.DEBUG && Debug.DEBUG_LOADER) {
			Debug.println("  mapped library name: " + mappedName); //$NON-NLS-1$
		}

		path = findNativePath(mappedName);

		if (path == null) {
			if (Debug.DEBUG && Debug.DEBUG_LOADER) {
				Debug.println("  library does not exist: " + mappedName); //$NON-NLS-1$
			}
			path = findNativePath(libname);
		}

		if (Debug.DEBUG && Debug.DEBUG_LOADER) {
			Debug.println("  returning library: " + path); //$NON-NLS-1$
		}
		return path;
	}

	/**
	 * Opens all resource for this BundleData.  Reopens the BundleData if
	 * it was previosly closed.
	 */
	public void open() throws IOException {
		baseBundleFile.open();
	}

	protected String findNativePath(String libname) {
		String path = null;
		if (!libname.startsWith("/")) { //$NON-NLS-1$
			libname = '/' + libname;
		}
		String[] nativepaths = getNativePaths();
		if (nativepaths != null) {
			for (int i = 0; i < nativepaths.length; i++) {
				if (nativepaths[i].endsWith(libname)) {
					File nativeFile = baseBundleFile.getFile(nativepaths[i]);
					path = nativeFile.getAbsolutePath();
				}
			}
		}
		return path;
	}

	/**
	 * Return the bundle generation directory.
	 * Attempt to create the directory if it does not exist.
	 *
	 * @return Bundle generation directory.
	 */

	/**
	 * Return the generation directory for the bundle data.  The generation
	 * directory can be used by the framework to cache files from the bundle
	 * to the file system.
	 * @return The generation directory for the bundle data or null if not
	 * supported.
	 */

	public File createGenerationDir() {
		File generationDir = getGenerationDir();
		if (!generationDir.exists() && !generationDir.mkdirs()) {
			if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
				Debug.println("Unable to create bundle generation directory: " + generationDir.getPath()); //$NON-NLS-1$
			}
		}

		return generationDir;
	}

	/**
	 * Return the base BundleFile for this BundleData.  The base BundleFile
	 * is the BundleFile that contains all the content of the bundle.
	 * @return the base BundleFile.
	 */
	public BundleFile getBaseBundleFile() {
		return baseBundleFile;
	}

	/**
	 * Close all resources for this BundleData
	 */
	public void close() throws IOException {
		if (baseBundleFile != null) {
			baseBundleFile.close();
		}
	}

	/**
	 * Return the bundle data directory.
	 * Attempt to create the directory if it does not exist.
	 *
	 * @return Bundle data directory.
	 */
	public File getDataFile(String path) {
		// lazily initialize dirData to prevent early access to instance location
		if (getDataDir() == null) {
			File dataRoot = adaptor.getDataRootDir();
			if (dataRoot == null)
				throw new IllegalStateException(AdaptorMsg.formatter.getString("ADAPTOR_DATA_AREA_NOT_SET")); //$NON-NLS-1$
			setDataDir(new File(dataRoot, id + "/" + AbstractFrameworkAdaptor.DATA_DIR_NAME)); //$NON-NLS-1$
		}
		if (!getDataDir().exists() && !getDataDir().mkdirs()) {
			if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
				Debug.println("Unable to create bundle data directory: " + getDataDir().getPath()); //$NON-NLS-1$
			}
		}

		return (new File(getDataDir(), path));
	}

	/**
	 * Installs the native code paths for this BundleData.  Each
	 * element of nativepaths must be installed for lookup when findLibrary 
	 * is called.
	 * @param nativepaths The array of native code paths to install for
	 * the bundle.
	 * @throws BundleException If any error occurs during install.
	 */
	public void installNativeCode(String[] nativepaths) throws BundleException {
		StringBuffer sb = new StringBuffer();
		for (int i = 0; i < nativepaths.length; i++) {
			// extract the native code
			File nativeFile = baseBundleFile.getFile(nativepaths[i]);
			if (nativeFile == null) {
				throw new BundleException(AdaptorMsg.formatter.getString("BUNDLE_NATIVECODE_EXCEPTION", nativepaths[i])); //$NON-NLS-1$
			}
			sb.append(nativepaths[i]);
			if (i < nativepaths.length - 1) {
				sb.append(","); //$NON-NLS-1$
			}
		}
		if (sb.length() > 0)
			setNativePaths(sb.toString());
	}

	protected File getGenerationDir() {
		return new File(getBundleStoreDir(), String.valueOf(getGeneration()));
	}
}