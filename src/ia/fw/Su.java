package ia.fw;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Date;
import java.util.regex.Pattern;

import android.util.Log;

public final class Su {
	private static final String TAG = Su.class.getSimpleName();

	public static interface Listener {
		void stdOut(String line);

		void stdErr(String line);

		void error(Throwable throwable);
	}

	// For events
	private Listener listener;

	public Su(final Listener listener) throws IOException {
		this.listener = listener;

		Log.d(TAG, "Starting process");
		su = new ProcessBuilder("su").start();

		Log.v(TAG, "Fetching standard streams");
		stdIn = new OutputStreamWriter(su.getOutputStream());
		_stdOut = new BufferedReader(new InputStreamReader(su.getInputStream()));
		_stdErr = new BufferedReader(new InputStreamReader(su.getErrorStream()));

		Log.d(TAG, "Waiting until su executes commands");
		final String now = Long.toString(new Date().getTime());
		stdIn.write("echo ");
		stdIn.write(now);
		stdIn.write("\n");
		stdIn.flush();
		final String line = _stdOut.readLine();
		if (line == null || !line.trim().equals(now))
			throw new RuntimeException("Could not get adminitration privileges");

		Log.i(TAG, "Started shell with administration privileges");

		Log.v(TAG, "Create threads for monitor, standard output and error");
		new Thread(TAG + " waitFor") {
			@Override
			public void run() {
				waitFor = Thread.currentThread();
				waitFor();
				waitFor = null;
			}
		}.start();
		new Thread(TAG + " stdErr") {
			@Override
			public void run() {
				stdErr = Thread.currentThread();
				stdErr();
				stdErr = null;
			}
		}.start();
		new Thread(TAG + " stdOut") {
			@Override
			public void run() {
				stdOut = Thread.currentThread();
				stdOut();
				stdOut = null;
			}
		}.start();

		while (waitFor == null || stdErr == null || stdOut == null)
			Thread.yield();
		Log.v(TAG, "Threads started");
	}

	// Disallow access to the process of su shell
	private Process su;

	private Thread waitFor;
	private Thread stdErr;
	private Thread stdOut;

	public boolean isRunning() {
		return waitFor != null || stdErr != null || stdOut != null;
	}

	public void stop() {
		if (!isRunning()) {
			Log.w(TAG, "Already stopped");
			return;
		}

		Log.d(TAG, "Stopping");

		try {
			// Let's be kind and ask su to exit normally
			stdIn("exit");
		} catch (final IOException e) {
			// Hm, maybe su already did what we wanted?
			Log.w(TAG, "Writing to su failed, already dead?");
		}

		try {
			// Save it because the thread can stop anytime
			// This avoids NullPointerExceptions
			final Thread waitFor = this.waitFor;

			if (waitFor != null) {
				// Give it some time
				Log.v(TAG, "Waiting for process to quit");
				waitFor.join(3000);
			}
		} catch (final InterruptedException e) {
			Log.w(TAG, "Got interrupt while waiting for process to quit");
		}

		// Better have a look at it, maybe su did not listen?
		if (waitFor != null) {
			// su has been a bad kid, time for a slap into the face
			Log.w(TAG, "Timeout waiting for process to quit, destroying it");
			su.destroy();
		}

		// Finally nothing else can be done
		Log.v(TAG, "Waiting for all threads to finish");
		while (isRunning())
			Thread.yield();

		su = null;

		listener = null;

		Log.d(TAG, "Stopped");
	}

	// -------------------------------------------------------------------------

	// This function lasts as long as the su process exists
	private void waitFor() {
		final String TAG = waitFor.getName();
		Log.d(TAG, "Monitoring process");

		while (true)
			try {
				Log.v(TAG, "Process finished with exit code: " + su.waitFor());
				break;
			} catch (final InterruptedException e) {
				Log.w(TAG, "Interrupt ignored");
			}
	}

	// Disallow access to standard input of su shell
	// Only this class is allowed to execute commands as root
	private final OutputStreamWriter stdIn;

	private void stdIn(final String command) throws IOException {
		if (!isRunning())
			throw new IllegalStateException("SU already stopped");

		Log.v(TAG, "Executing as root: " + command);
		stdIn.write(command);
		stdIn.write("\n");
		stdIn.flush();
	}

	// Instead of directly accessing standard error and output, send events
	private final BufferedReader _stdErr;
	private final BufferedReader _stdOut;

	private void stdErr() {
		final String TAG = stdErr.getName();
		Log.d(TAG, "Reading standard error");

		try {
			String line;
			// TODO: Assumes there is a text line based output
			while ((line = _stdErr.readLine()) != null)
				listener.stdErr(line);
			Log.v(TAG, "Finished normally");
		} catch (final Throwable t) {
			Log.w(TAG, t.getMessage(), t);
			listener.error(t);
		}
	}

	private void stdOut() {
		final String TAG = stdOut.getName();
		Log.d(TAG, "Reading standard output");

		try {
			String line;
			// TODO: Assumes there is a text line based output
			while ((line = _stdOut.readLine()) != null)
				listener.stdOut(line);
			Log.v(TAG, "Finished normally");
		} catch (final Throwable t) {
			Log.w(TAG, t.getMessage(), t);
			listener.error(t);
		}
	}

	// -------------------------------------------------------------------------
	// Allow reading of kernel messages

	private final static int CAT_KMSG_DEAD = -2;
	private final static int CAT_KMSG_START = -1;
	private int pidCatKmsg = CAT_KMSG_DEAD;

	public boolean readsKernelMessages() {
		return pidCatKmsg >= 0;
	}

	public void readKernelMessages() throws IOException {
		if (pidCatKmsg != CAT_KMSG_DEAD)
			throw new IllegalStateException("Already reading kernel messages");

		pidCatKmsg = CAT_KMSG_START;

		Log.d(TAG, "Start cat to read kernel messages");

		Log.v(TAG, "Replacing listener to receive first line of cat");
		final Listener listener = this.listener;
		this.listener = new Listener() {
			@Override
			public void stdOut(final String line) {
				pidCatKmsg = Integer.parseInt(line.split(" ")[0]);
				Log.v(TAG, "PID for backgrounded cat is " + pidCatKmsg);

				Log.v(TAG, "Restoring original listener");
				Su.this.listener = listener;

				Log.d(TAG, "Reading kernel messages in background");
			}

			@Override
			public void stdErr(final String line) {
				listener.stdErr(line);
			}

			@Override
			public void error(final Throwable throwable) {
				listener.error(throwable);
			}
		};
		// File /proc/self/stat contains one line
		// First word is process id of the process that opened /proc/self/stat
		// More information to be found via 'man proc' and search for ']/stat'
		// So at first cat will read and output this file and afterwards
		// begin to read /proc/kmesg which
		stdIn("cat /proc/self/stat /proc/kmsg &");

		Log.v(TAG, "Wait for cat to output process id");
	}

	public void stopReadKernelMessages() throws IOException {
		if (!readsKernelMessages())
			throw new IllegalArgumentException("Not reading kernel messages");

		Log.d(TAG, "Stop reading kernel messages in background");

		stdIn("kill " + pidCatKmsg);

		// TODO: Check 'echo $?' for exit code of kill
		pidCatKmsg = CAT_KMSG_DEAD;
	}

	// -------------------------------------------------------------------------
	// Allow all access to iptables

	// Make sure no characters handled by shell get through
	private static Pattern argsValidChars = Pattern.compile("^[ !\"#"/* $ */
			+ "%"/* & */+ "'()"/* * */
			+ "+,\\-./0-9:"/* ;< */+ "="/* > */+ "?@A-Z\\[\\\\\\]^_"/* ` */
			+ "a-z{"/* | */+ "}"/* ~ */+ "]+$");

	// In case someone tried it, let him know
	public static class IptablesArgsException extends RuntimeException {
		private static final long serialVersionUID = 8116321773534179178L;
	}

	public void iptables(final String args) throws IOException {
		if (!isRunning())
			throw new IllegalStateException("SU already stopped");

		if (!argsValidChars.matcher(args).matches())
			throw new IptablesArgsException();

		Log.i(TAG, "New iptables command: " + args);
		stdIn("iptables " + args);
	}
}