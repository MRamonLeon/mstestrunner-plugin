package org.jenkinsci.plugins;

import hudson.*;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.tasks.Builder;
import hudson.tools.ToolInstallation;
import hudson.util.ArgumentListBuilder;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.util.StringTokenizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author Ido Ran
 */
public class MsTestBuilder extends Builder {

    /**
     * GUI fields
     */
    private final String msTestName;
    private final String testFiles;
    private final String categories;
    private final String resultFile;
    private final String cmdLineArgs;
    private final boolean continueOnFail;

    /**
     * When this builder is created in the project configuration step,
     * the builder object will be created from the strings below.
     *
     * @param msTestName The MSTest logical name
     * @param testFiles The path of the test files
     * @param cmdLineArgs Whitespace separated list of command line arguments
     */
    @DataBoundConstructor
    @SuppressWarnings("unused")
    public MsTestBuilder(String msTestName, String testFiles, String categories, String resultFile, String cmdLineArgs, boolean continueOnFail) {
        this.msTestName = msTestName;
        this.testFiles = testFiles;
        this.categories = categories;
        this.resultFile = resultFile;
        this.cmdLineArgs = cmdLineArgs;
        this.continueOnFail = continueOnFail;
    }

    @SuppressWarnings("unused")
    public String getCmdLineArgs() {
        return cmdLineArgs;
    }

    @SuppressWarnings("unused")
    public String getTestFiles() {
        return testFiles;
    }

    @SuppressWarnings("unused")
    public String getCategories() {
        return categories;
    }

    @SuppressWarnings("unused")
    public String getResultFile() {
        return resultFile;
    }

    @SuppressWarnings("unused")
    public String getMsTestName() {
        return msTestName;
    }
    
    @SuppressWarnings("unused")
    public boolean getcontinueOnFail() {
        return continueOnFail;
    }

    public MsTestInstallation getMsTest() {
        for (MsTestInstallation i : DESCRIPTOR.getInstallations()) {
            if (msTestName != null && i.getName().equals(msTestName)) {
                return i;
            }
        }
        return null;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        List<String> args = new ArrayList<String>();

        // Build MSTest.exe path.
        String execName = "mstest.exe";
        MsTestInstallation installation = getMsTest();
        if (installation == null) {
            listener.getLogger().println("Path To MSTest.exe: " + execName);
            args.add(execName);
        } else {
            EnvVars env = build.getEnvironment(listener);
            installation = installation.forNode(Computer.currentComputer().getNode(), listener);
            installation = installation.forEnvironment(env);
            String pathToMsTest = installation.getHome();
            FilePath exec = new FilePath(launcher.getChannel(), pathToMsTest);
            try {
                if (!exec.exists()) {
                    listener.fatalError(pathToMsTest + " doesn't exist");
                    return false;
                }
            } catch (IOException e) {
                listener.fatalError("Failed checking for existence of " + pathToMsTest);
                return false;
            }
            listener.getLogger().println("Path To MSTest.exe: " + pathToMsTest);
            args.add(pathToMsTest);

            if (installation.getDefaultArgs() != null) {
                args.addAll(Arrays.asList(Util.tokenize(installation.getDefaultArgs())));
            }
        }
        
        if (resultFile == null || resultFile.trim().length() == 0) {
            listener.fatalError("Result file name was not specified");
            return false;
        }
        
        // Delete old result file
        FilePath resultFilePath = build.getWorkspace().child(resultFile);
        if (!resultFilePath.exists()) {
            listener.getLogger().println("Result file was not found so no action has been taken. " + resultFilePath.toURI());
        } else {
            listener.getLogger().println("Delete old result file " + resultFilePath.toURI().toString());
            try {
                resultFilePath.delete();
            } catch (IOException ex) {
                ex.printStackTrace(listener.fatalError("Fail to delete old result file"));
                return false;
            } catch (InterruptedException ex) {
                ex.printStackTrace(listener.fatalError("Fail to delete old result file"));
                return false;
            }
        }
        
        // Add result file argument
        args.add("/resultsfile:" + resultFile);
        
        // Checks to use noisolation flag
        if (!installation.getOmitNoIsolation()){
            args.add("/noisolation");
        }

        // Add command line arguments
        EnvVars env = build.getEnvironment(listener);
        String normalizedArgs = cmdLineArgs.replaceAll("[\t\r\n]+", " ");
        normalizedArgs = Util.replaceMacro(normalizedArgs, env);
        normalizedArgs = Util.replaceMacro(normalizedArgs, build.getBuildVariables());
        if (normalizedArgs.trim().length() > 0) {
            args.addAll(Arrays.asList(Util.tokenize(normalizedArgs)));
        }

        if (categories != null && categories.trim().length() > 0) {
            args.add("/category:\"" + categories.trim() + "\"");
        }

        // if no test files are specified fail the build.
        if (testFiles == null || testFiles.trim().length() == 0) {
            listener.fatalError("No test files are specified");
            return false;
        }

        // Add test containers to command line
        String macroReplacedTestFiles = Util.replaceMacro(testFiles, env);// Required to handle newlines properly
        StringTokenizer testFilesToknzr = new StringTokenizer(macroReplacedTestFiles, "\r\n");
        while (testFilesToknzr.hasMoreTokens()) {
            String testFile = testFilesToknzr.nextToken();
            testFile = Util.replaceMacro(testFile, env);
            testFile = Util.replaceMacro(testFile, build.getBuildVariables());

            if (testFile.length() > 0) {
                args.add("/testcontainer:" + testFile);
            }
        }

        try {
            int r = launcher.launch().cmds(args).envs(env).stdout(listener).pwd(build.getWorkspace()).join();
            
            // If continueOnFail is set we always report success.
            // If not we report success if MSTest return 0 and exit value.
            return continueOnFail || (r == 0);
        } catch (IOException e) {
            Util.displayIOException(e, listener);
            e.printStackTrace(listener.fatalError("MSTest command execution failed"));
            return false;
        }
    }

    @Override
    public Descriptor<Builder> getDescriptor() {
        return DESCRIPTOR;
    }
    /**
     * Descriptor should be singleton.
     */
    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static final class DescriptorImpl extends Descriptor<Builder> {

        @CopyOnWrite
        private volatile MsTestInstallation[] installations = new MsTestInstallation[0];

        DescriptorImpl() {
            super(MsTestBuilder.class);
            load();
        }

        public String getDisplayName() {
            return "Run unit tests with MSTest";
        }

        public MsTestInstallation[] getInstallations() {
            return installations;
        }

        public void setInstallations(MsTestInstallation... antInstallations) {
            this.installations = antInstallations;
            save();
        }

        /**
         * Obtains the {@link MsTestInstallation.DescriptorImpl} instance.
         */
        public MsTestInstallation.DescriptorImpl getToolDescriptor() {
            return ToolInstallation.all().get(MsTestInstallation.DescriptorImpl.class);
        }
    }
}
