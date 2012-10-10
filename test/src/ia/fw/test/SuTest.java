package ia.fw.test;

import ia.fw.Su;

import java.util.Timer;
import java.util.TimerTask;

import junit.framework.TestCase;
import android.util.Log;

public class SuTest extends TestCase {
	public static final String TAG = SuTest.class.getSimpleName();

	/**
	 * This function helps testing the execution time of a single line. It needs
	 * to be placed right on top of the line that need to be tested. It does
	 * this via saving filename and linenumber of the code that is executed
	 * after this function. When [timeout] milliseconds have past, a comparison
	 * between the old and new filenames und linenumbers is done. If they are
	 * still the same the test has failed and [message] is given as reason.
	 * 
	 * @param timeout
	 *            The test should fail after this many milliseconds
	 * @param message
	 *            Provide this message as reason for test to fail
	 */
	private static void failIn(final long timeout, final String message) {
		final Thread thread = Thread.currentThread();
		final StackTraceElement[] stack = thread.getStackTrace();
		// Shift 3 elements:
		// stack[0] is a native function
		// stack[1] is Thread.currentThread.getStackTrace()
		// stack[2] is here, I mean this static function failIn(...)
		// stack[3] is the place where this function was called
		final String file = stack[3].getFileName();
		// [line] is the code that is executed after this function.
		final int line = stack[3].getLineNumber() + 1;
		// This scheme applies to Thread.currentThread.getStackTrace().
		// Calling thread.getStackTrace() from another thread looks different.
		// Therefore the position of the code under test in the stack is saved
		// by difference of the earliest element of the stack.
		final int depth = stack.length - 3;

		new Timer().schedule(new TimerTask() {
			@Override
			public void run() {
				final StackTraceElement[] stack = thread.getStackTrace();
				if (depth > stack.length)
					return;

				final StackTraceElement element = stack[stack.length - depth];
				final String currentFile = element.getFileName();
				final int currentLine = element.getLineNumber();

				if (file.equals(currentFile) && line == currentLine)
					// Filename and linenumber did not change, test failed!
					fail(message);
			}
		}, timeout);
	}

	// --------------------------------------------------------------------------

	Su su;

	static class SuListenerAdapter implements Su.Listener {
		@Override
		public void stdOut(final String line) {
			fail("Unexpected output on standard output: " + line);
		}

		@Override
		public void stdErr(final String line) {
			fail("Unexpected output on standard error: " + line);
		}

		@Override
		public void error(final Throwable throwable) {
			Log.e(TAG, throwable.getMessage(), throwable);
			fail(throwable.getMessage());
		}
	}

	public void testLifecycle() throws Throwable {
		failIn(6000, "Timeout: new Su(...)");
		su = new Su(new SuListenerAdapter());

		assertTrue(su.isRunning());
		failIn(3000, "Timeout: su.stop()");
		su.stop();
		assertFalse(su.isRunning());

		try {
			su.stop();

			fail("Could call stop() a 2nd time");
		} catch (final Throwable t) {
		}
	}
}