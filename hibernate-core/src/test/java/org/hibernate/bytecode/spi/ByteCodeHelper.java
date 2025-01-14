/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.bytecode.spi;

import java.io.IOException;
import java.io.InputStream;

import org.hibernate.internal.util.collections.ArrayHelper;

/**
 * A helper for reading byte code from various input sources.
 *
 * @author Steve Ebersole
 */
public class ByteCodeHelper {
	/**
	 * Disallow instantiation (its a helper)
	 */
	private ByteCodeHelper() {
	}

	/**
	 * Reads class byte array info from the given input stream.
	 *
	 * The stream is closed within this method!
	 *
	 * @param inputStream The stream containing the class binary; null will lead to an {@link IOException}
	 *
	 * @return The read bytes
	 *
	 * @throws IOException Indicates a problem accessing the given stream.
	 */
	public static byte[] readByteCode(InputStream inputStream) throws IOException {
		if ( inputStream == null ) {
			throw new IOException( "null input stream" );
		}

		final byte[] buffer = new byte[409600];
		byte[] classBytes = ArrayHelper.EMPTY_BYTE_ARRAY;

		try {
			int r = inputStream.read( buffer );
			while ( r >= buffer.length ) {
				final byte[] temp = new byte[ classBytes.length + buffer.length ];
				// copy any previously read bytes into the temp array
				System.arraycopy( classBytes, 0, temp, 0, classBytes.length );
				// copy the just read bytes into the temp array (after the previously read)
				System.arraycopy( buffer, 0, temp, classBytes.length, buffer.length );
				classBytes = temp;
				// read the next set of bytes into buffer
				r = inputStream.read( buffer );
			}
			if ( r != -1 ) {
				final byte[] temp = new byte[ classBytes.length + r ];
				// copy any previously read bytes into the temp array
				System.arraycopy( classBytes, 0, temp, 0, classBytes.length );
				// copy the just read bytes into the temp array (after the previously read)
				System.arraycopy( buffer, 0, temp, classBytes.length, r );
				classBytes = temp;
			}
		}
		finally {
			try {
				inputStream.close();
			}
			catch (IOException ignore) {
				// intentionally empty
			}
		}

		return classBytes;
	}

}
