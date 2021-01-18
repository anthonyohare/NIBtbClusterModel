package btbcluster;

import broadwick.BroadwickException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli2.CommandLine;
import org.apache.commons.cli2.Group;
import org.apache.commons.cli2.Option;
import org.apache.commons.cli2.builder.ArgumentBuilder;
import org.apache.commons.cli2.builder.DefaultOptionBuilder;
import org.apache.commons.cli2.builder.GroupBuilder;
import org.apache.commons.cli2.commandline.Parser;
import org.apache.commons.cli2.util.HelpFormatter;

/**
 * Construct and read command line arguments. This class contains methods for extracting some of
 * the main options such as configuration file etc.
 */
@Slf4j
public class CommandLineOptions {

    /**
     * Construct and provide GNU-compatible Options. Read the command line extracting the arguments,
     * this additionally displays the help message if the command line is empty.
     *
     * @param args the command line arguments.
     */
    public CommandLineOptions(final String[] args) {
        buildCommandLineArguments();

        final Parser parser = new Parser();
        parser.setGroup(options);
        final HelpFormatter hf = new HelpFormatter(SPACE, SPACE, SPACE, LINEWIDTH);
        parser.setHelpFormatter(hf);
        parser.setHelpTrigger("--help");
        cmdLine = parser.parseAndHelp(args);

        log.debug("options = {}", cmdLine);

        if (cmdLine == null) {
            hf.printHeader();
            throw new BroadwickException("Invalid command line options; see help message.");
        }
    }

    /**
     * Construct and provide GNU-compatible Options.
     */
    private void buildCommandLineArguments() {

        final DefaultOptionBuilder obuilder = new DefaultOptionBuilder();
        final ArgumentBuilder abuilder = new ArgumentBuilder();
        final GroupBuilder gbuilder = new GroupBuilder();

        configFileNameOpt = obuilder.withShortName("config")
                .withShortName("c")
                .withDescription("The name of the config file.")
                .withArgument(
                        abuilder.withName("config")
                                .withMinimum(1)
                                .withMaximum(1)
                                .create())
                .create();
        paramFileNameOpt = obuilder.withShortName("params")
                .withShortName("p")
                .withDescription("The name of the file containing the parameters for the scenario.")
                .withArgument(
                        abuilder.withName("params")
                                .withMinimum(1)
                                .withMaximum(1)
                                .create())
                .create();
        idOpt = obuilder.withShortName("id")
                .withShortName("i")
                .withDescription("The id given to this scenario.")
                .withArgument(
                        abuilder.withName("id")
                                .withMinimum(1)
                                .withMaximum(1)
                                .create())
                .create();
        logOpt = obuilder.withShortName("level")
                .withShortName("l")
                .withDescription("The logging level for the file log.")
                .withArgument(
                        abuilder.withName("level")
                                .withMinimum(0)
                                .withMaximum(1)
                                .create())
                .create();

        options = gbuilder.withName("options")
                .withOption(configFileNameOpt)
                .withOption(paramFileNameOpt)
                .withOption(idOpt)
                .withOption(logOpt)
                .create();
    }

    /**
     * Get the name of the file containing the configurations for the scenario. The name of the file
     * must be relative to the executeable jar.
     * @return the name of the config file specified by the -c option.
     */
    public final String getConfigFileName() {
        return getOpt(configFileNameOpt);
    }

    /**
     * Get the name of the file containing the parameters.
     * @return a string with the name of the file containing the parameters specified by the -p option.
     */
    public final String getParameterFileName() {
        return getOpt(paramFileNameOpt);
    }

    /**
     * Get the id for the scenario.
     * @return a string with the scenario's id.
     */
    public final String getScenarioId() {
        return getOpt(idOpt);
    }

    /**
     * Get the id for the scenario.
     * @return a string with the scenario's id.
     */
    public final String getLoggingLevel() {
        String level = getOpt(logOpt);
        if (level.isEmpty()) {
            level = "INFO";
        }
        return level;
    }

    /**
     * Get the string that accompanies this option or "" if there is none. For example, if the
     * command line is "-c config.xml" and this method is called with the "-c" option it will
     * return "config.xml".
     * @param option the option whose string is required
     * @return the string that is specified by this option.
     */
    private String getOpt(final Option option) {
        if (cmdLine.hasOption(option)) {
            return (String) cmdLine.getValue(option);
        } else {
            return "";
        }
    }

    private final CommandLine cmdLine;
    private Group options;
    private static final int LINEWIDTH = 120;
    private static final String SPACE = " ";
    private Option configFileNameOpt;
    private Option paramFileNameOpt;
    private Option idOpt;
    private Option logOpt;
}
