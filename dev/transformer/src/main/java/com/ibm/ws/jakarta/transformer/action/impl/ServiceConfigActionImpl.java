package com.ibm.ws.jakarta.transformer.action.impl;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.Set;

import com.ibm.ws.jakarta.transformer.JakartaTransformException;
import com.ibm.ws.jakarta.transformer.action.BundleData;
import com.ibm.ws.jakarta.transformer.action.ServiceConfigAction;
import com.ibm.ws.jakarta.transformer.util.ByteData;

/**
 * Transform service configuration bytes.
 * 
 * Per: https://docs.oracle.com/javase/6/docs/api/java/util/ServiceLoader.html
 *	
 * A service provider is identified by placing a provider-configuration file in the
 * resource directory META-INF/services. The file's name is the fully-qualified binary
 * name of the service's type. The file contains a list of fully-qualified binary names
 * of concrete provider classes, one per line. Space and tab characters surrounding each
 * name, as well as blank lines, are ignored. The comment character is '#' ('\u0023', NUMBER SIGN);
 * on each line all characters following the first comment character are ignored. The file
 * must be encoded in UTF-8.
 */
public class ServiceConfigActionImpl extends ActionImpl implements ServiceConfigAction {

	public ServiceConfigActionImpl(ActionImpl parentAction) {
		super(parentAction);
	}

	public ServiceConfigActionImpl(Set<String> includes, Set<String> excludes, Map<String, String> renames,
			Map<String, String> versions, Map<String, BundleData> bundleUpdates) {
		super(includes, excludes, renames, versions, bundleUpdates);
	}

	public ServiceConfigActionImpl(
		PrintStream logStream, boolean isTerse, boolean isVerbose,
		Set<String> includes, Set<String> excludes, Map<String, String> renames,
		Map<String, String> versions, Map<String, BundleData> bundleUpdates) {

		super(logStream, isTerse, isVerbose,
			  includes, excludes,
			  renames, versions, bundleUpdates);
	}

	//

	public String getName() {
		return "Service Config Action";
	}

	//

	@Override
	protected ServiceConfigChangesImpl newChanges() {
		return new ServiceConfigChangesImpl();
	}

	@Override
	public ServiceConfigChangesImpl getChanges() {
		return (ServiceConfigChangesImpl) super.getChanges();
	}

	protected void addUnchangedProvider() {
		getChanges().addUnchangedProvider();
	}

	protected void addChangedProvider() {
		getChanges().addChangedProvider();
	}

	//

	@Override
	public boolean accept(String resourceName) {
		return resourceName.contains(META_INF_SERVICES);
	}

	//

	@Override
	public ByteData apply(String inputName, byte[] inputBytes, int inputLength) 
		throws JakartaTransformException {

		clearChanges();
		setResourceNames(inputName, inputName);

		InputStream inputStream = new ByteArrayInputStream(inputBytes);
		InputStreamReader inputReader;
		try {
			inputReader = new InputStreamReader(inputStream, "UTF-8");
		} catch ( UnsupportedEncodingException e ) {
			error("Strange: UTF-8 is an unrecognized encoding for reading [ %s ]\n", e, inputName);
			return null;
		}

		BufferedReader reader = new BufferedReader(inputReader);

		ByteArrayOutputStream outputStream = new ByteArrayOutputStream(inputBytes.length);
		OutputStreamWriter outputWriter;
		try {
			outputWriter = new OutputStreamWriter(outputStream, "UTF-8");
		} catch ( UnsupportedEncodingException e ) {
			error("Strange: UTF-8 is an unrecognized encoding for writing [ %s ]\n", e, inputName);
			return null;
		}

		BufferedWriter writer = new BufferedWriter(outputWriter);

		try {
			transform(reader, writer); // throws IOException
		} catch ( IOException e ) {
			error("Failed to transform [ %s ]\n", e, inputName);
			return null;
		}

		try {
			writer.flush(); // throws
		} catch ( IOException e ) {
			error("Failed to flush [ %s ]\n", e, inputName);
			return null;
		}

		if ( !hasChanges() ) {
			return null;
		}

		byte[] outputBytes = outputStream.toByteArray();
		return new ByteData(inputName, outputBytes, 0, outputBytes.length);
	}

	protected void transform(BufferedReader reader, BufferedWriter writer)
		throws IOException {

		String inputLine;
		while ( (inputLine = reader.readLine()) != null ) { // throws IOException
			// Goal is to find the input package name.  Find it by
			// successively taking text off of the input line.

			String inputPackageName;

			// The first '#' and all following characters are ignored.

			int poundLocation = inputLine.indexOf('#');
			if ( poundLocation != -1 ) {
				inputPackageName = inputLine.substring(0, poundLocation);
			} else {
				inputPackageName = inputLine;
			}

			// Leading and trailing whitespace which surrounds the fully
			// qualified name is ignored.  This step must be done after
			// trimming off a comment, since the trim must be of immediately
			// surrounding whitespace.

			inputPackageName = inputPackageName.trim();

			// Renames are performed on package names.  Per the documentation,
			// the values are fully qualified class names.

			int dotLocation;
			String outputPackageName;

			if ( inputPackageName.isEmpty() ) {
				// The line was either entirely blank space, or was just
				// comment.  There is no package to rename.
				dotLocation = -1;
				outputPackageName = null;

			} else {
				dotLocation = inputLine.lastIndexOf('.');
				if ( dotLocation == -1 ) {
					// A class which uses the default package: There is no package
					// to rename.
					outputPackageName = null;
				} else if ( dotLocation == 0 ) {
					// Strange leading ".": Ignore it.
					outputPackageName = null;
				} else {
					// Nab just the fully qualified package name.
					inputPackageName = inputPackageName.substring(0, dotLocation);
					// And perform any renames which apply.
					outputPackageName = replacePackage(inputPackageName);
				}
			}

			String outputLine;

			if ( outputPackageName == null ) {
				// For one of the reasons, above, no rename was performed on the line.
				outputLine = inputLine;
				addUnchangedProvider();

			} else {
				// Not most efficient, but good enough:
				// Service configuration files are expected to have only a few
				// values, and these are expected to use little or no white space.

				// Figure where the input fully qualified package name began and ended.

				int inputPackageStart = inputLine.indexOf(inputPackageName);
				int inputPackageEnd = inputPackageStart + dotLocation;
		
				// Recover as much of the original file as possible.

				outputLine =
					inputLine.substring(0, inputPackageStart) +
					outputPackageName +
					inputLine.substring(inputPackageEnd);

				addChangedProvider();
			}

			writer.write(outputLine); // throws IOException
			writer.newLine(); // throws IOException
		}
	}
}
