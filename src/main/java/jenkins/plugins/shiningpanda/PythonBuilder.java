/*
 * ShiningPanda plug-in for Jenkins
 * Copyright (C) 2011 ShiningPanda S.A.S.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package jenkins.plugins.shiningpanda;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.model.Computer;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.remoting.VirtualChannel;
import hudson.tasks.Builder;
import hudson.tasks.Messages;
import hudson.tasks.Shell;

import java.io.IOException;
import java.io.Serializable;
import java.util.Map;

public abstract class PythonBuilder extends Builder implements Serializable
{

    /**
     * Do not consider the build as a failure if any of the commands exits with
     * a non-zero exit code.
     */
    public final boolean ignoreExitCode;

    /**
     * The command to execute in the PYTHON environment
     */
    public final String command;

    /**
     * Constructor using fields
     * 
     * @param ignoreExitCode
     *            Do not consider the build as a failure if any of the commands
     *            exits with a non-zero exit code
     * @param command
     *            The command to execute in PYTHON environment
     */
    public PythonBuilder(boolean ignoreExitCode, String command)
    {
        // Call super
        super();
        // Store the ignore flag
        this.ignoreExitCode = ignoreExitCode;
        // Normalize and store the command
        this.command = fixCrLf(command);
    }

    /**
     * Set PYTHON environment
     * 
     * @param envVars
     *            The environment to update
     * @param build
     *            The build
     * @param node
     *            The node where we run on
     * @param launcher
     *            The task launcher
     * @param listener
     *            The listener
     * @return Return true if environment was successfully set
     * @throws InterruptedException
     * @throws IOException
     */
    protected abstract boolean setEnvironment(EnvVars envVars, AbstractBuild<?, ?> build, Node node, Launcher launcher,
            TaskListener listener) throws InterruptedException, IOException;

    /*
     * (non-Javadoc)
     * 
     * @see
     * hudson.tasks.BuildStepCompatibilityLayer#perform(hudson.model.AbstractBuild
     * , hudson.Launcher, hudson.model.BuildListener)
     */
    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException
    {
        return perform(build, launcher, (TaskListener) listener);
    }

    /**
     * Perform the build
     * 
     * @param build
     *            The build
     * @param launcher
     *            The task launcher
     * @param listener
     *            The listener
     * @return Return true if the build went well
     * @throws InterruptedException
     */
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, TaskListener listener) throws InterruptedException
    {
        FilePath ws = build.getWorkspace();
        FilePath script = null;
        try
        {
            try
            {
                script = createScriptFile(ws);
            }
            catch (IOException e)
            {
                Util.displayIOException(e, listener);
                e.printStackTrace(listener.fatalError(Messages.CommandInterpreter_UnableToProduceScript()));
                return false;
            }
            int r;
            try
            {
                // Get the environment variables for this build
                EnvVars envVars = build.getEnvironment(listener);
                // Add build variables, such as the user defined text axis for
                // matrix builds
                for (Map.Entry<String, String> e : build.getBuildVariables().entrySet())
                    // Add the variable
                    envVars.put(e.getKey(), e.getValue());
                // Set the environment of this specific builder
                if (!setEnvironment(envVars, build, Computer.currentComputer().getNode(), launcher, listener))
                    // If fails to set the environment, make the build fail
                    return false;
                // Start command line
                r = launcher.launch().cmds(buildCommandLine(script)).envs(envVars).stdout(listener).pwd(ws).join();
            }
            catch (IOException e)
            {
                Util.displayIOException(e, listener);
                e.printStackTrace(listener.fatalError(Messages.CommandInterpreter_CommandFailed()));
                r = -1;
            }
            return r == 0;
        }
        finally
        {
            try
            {
                if (script != null)
                    script.delete();
            }
            catch (IOException e)
            {
                Util.displayIOException(e, listener);
                e.printStackTrace(listener.fatalError(Messages.CommandInterpreter_UnableToDelete(script)));
            }
        }
    }

    /**
     * Creates a script file in a temporary name in the specified directory
     * 
     * @param dir
     *            The directory
     * @return The file path object
     * @throws IOException
     * @throws InterruptedException
     */
    public FilePath createScriptFile(FilePath dir) throws IOException, InterruptedException
    {
        return dir.createTextTempFile("shiningpanda", getFileExtension(), getContents(), false);
    }

    /**
     * Fix CR/LF and always make it Unix style
     * 
     * @param s
     *            The string to fix
     * @return The fixed string
     */
    private static String fixCrLf(String s)
    {
        int idx;
        while ((idx = s.indexOf("\r\n")) != -1)
            s = s.substring(0, idx) + s.substring(idx + 1);
        return s;
    }

    /**
     * Older versions of bash have a bug where non-ASCII on the first line makes
     * the shell think the file is a binary file and not a script. Adding a
     * leading line feed works around this problem.
     * 
     * @param s
     *            The string to fix
     * @return The fixed string
     */
    private static String addCrForNonASCII(String s)
    {
        if (s.indexOf('\n') != 0)
            return "\n" + s;
        return s;
    }

    /**
     * Build the command line to call the script
     * 
     * @param script
     *            The path of the script
     * @return The command line to call to execute the script file
     */
    public String[] buildCommandLine(FilePath script)
    {
        return new String[] { getShell(script.getChannel()), ignoreExitCode ? "-x" : "-xe", script.getRemote() };
    }

    /**
     * Get a shell
     * 
     * @param channel
     *            The channel
     * @return The shell to use to launch the script file
     */
    protected String getShell(VirtualChannel channel)
    {
        return Hudson.getInstance().getDescriptorByType(Shell.DescriptorImpl.class).getShellOrDefault(channel);
    }

    /**
     * Get the content of the script file
     * 
     * @return The content of the file
     */
    protected String getContents()
    {
        return addCrForNonASCII(fixCrLf(command)) + "\n" + "exit 0";
    }

    /**
     * Get the file extension for the script
     * 
     * @return The extension
     */
    protected String getFileExtension()
    {
        return ".sh";
    }

    /**
     * Get a new VIRTUALENV
     * 
     * @param home
     *            The home folder of the VIRTUALENV
     * @param build
     *            The build
     * @param node
     *            The node where it will run on
     * @param listener
     *            The task listener
     * @param envVars
     *            The environment variables
     * @return The new installation
     * @throws InterruptedException
     * @throws IOException
     */
    public VirtualenvInstallation getVirtualenv(String home, AbstractBuild<?, ?> build, Node node, TaskListener listener,
            EnvVars envVars) throws InterruptedException, IOException
    {
        VirtualenvInstallation vi = new VirtualenvInstallation(home);
        vi = vi.forNode(node, listener);
        vi = vi.forEnvironment(envVars);
        return vi;
    }

    private static final long serialVersionUID = 1L;

}
