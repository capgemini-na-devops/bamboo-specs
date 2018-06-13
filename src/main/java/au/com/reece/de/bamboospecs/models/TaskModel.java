package au.com.reece.de.bamboospecs.models;

import au.com.reece.de.bamboospecs.models.docker.DockerContainer;
import au.com.reece.de.bamboospecs.models.docker.PortMapping;
import au.com.reece.de.bamboospecs.models.docker.VolumeMapping;
import au.com.reece.de.bamboospecs.models.docker.DockerStartCheck;
import au.com.reece.de.bamboospecs.models.enums.TaskType;
import au.com.reece.de.bamboospecs.models.enums.InjectScopeType;
import com.atlassian.bamboo.specs.api.builders.task.Task;
import com.atlassian.bamboo.specs.builders.task.*;
import com.atlassian.bamboo.specs.model.task.TestParserTaskProperties;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

public class TaskModel extends DomainModel {
    @NotNull
    public TaskType type;

    // Used by all task types
    @NotNull
    @NotEmpty
    public String description;

    // Used by: SCRIPT
    public String body;

    // Used by: JUNIT
    public String resultFrom;

    // Used by: VCS
    public List<RepositoryModel> repositories;
    public boolean cleanCheckout = false;
    public boolean defaultRepository = false;

    // Used by: DOCKER
    public String image;
    public boolean detach = false;
    public DockerStartCheck serviceStartCheck;
    public String environmentVariables;
    public DockerContainer container;
    public VolumeMapping[] volumeMappings;
    public PortMapping[] portMappings;
    public String cmdLineArguments;

    // legacy attributes from DOCKER
    public String command;
    public String workingDirectory;
    // end legacy

    // Used by: INJECT
    public String propertiesFile;
    public String namespace;
    public InjectScopeType scope = InjectScopeType.RESULT;

    public Task asTask() {
        switch (this.type) {
            case VCS:
                return getVersionControlTask();
            case SCRIPT:
                return getScriptTask();
            case JUNIT:
                return getJunitTask();
            case DOCKER:
                return getDockerTask();
            case CLEAN:
                return new CleanWorkingDirectoryTask();
            case ARTEFACT:
                return getArtefactDownloadTask();
            case INJECT:
                return getInjectTask();
            default:
                // shouldn't actually be possible, given we load via enum
                throw new RuntimeException("Unexpected 'type' value from yaml " + this.type);
        }
    }

    private Task getInjectTask() {
        InjectVariablesTask task = new InjectVariablesTask().description(this.description);
        if (this.namespace == null || this.namespace.isEmpty()) {
            throw new RuntimeException("Missing 'namespace' value from yaml for INJECT");
        }
        if (this.propertiesFile == null || this.propertiesFile.isEmpty()) {
            throw new RuntimeException("Missing 'propertiesFile' value from yaml for INJECT");
        }
        task.namespace(this.namespace).path(this.propertiesFile);
        switch (this.scope) {
            case LOCAL:
                return task.scopeLocal();
            case RESULT:
                return task.scopeResult();
            default:
                throw new RuntimeException("Unexpected 'scope' value from yaml " + this.scope);
        }
    }

    private Task getVersionControlTask() {
        VcsCheckoutTask task = new VcsCheckoutTask().description(this.description);
        if (this.defaultRepository) {
            task.checkoutItems(new CheckoutItem().defaultRepository());
        }
        if (this.repositories != null) {
            for (RepositoryModel vcs : this.repositories) {
                task.checkoutItems(vcs.asCheckoutItem());
            }
        }
        if (this.cleanCheckout) {
            task.cleanCheckout(true);
        }
        return task;
    }

    private Task getScriptTask() {
        if (this.body == null) {
            throw new RuntimeException("Missing 'body' value from yaml for SCRIPT");
        }
        return new ScriptTask().description(this.description).inlineBody(this.body);
    }

    private Task getJunitTask() {
        if (this.resultFrom == null) {
            throw new RuntimeException("Missing 'resultFrom' value from yaml for JUNIT");
        }
        return new TestParserTask(TestParserTaskProperties.TestType.JUNIT)
                .description(this.description)
                .resultDirectories(this.resultFrom);
    }

    private Task getArtefactDownloadTask() {
        return new ArtifactDownloaderTask().artifacts(new DownloadItem().allArtifacts(true));
    }

    private Task getDockerTask() {
        if (this.image == null) {
            throw new RuntimeException("DOCKER tasks require 'image' to be set");
        }

        // note the serviceURLPattern comes from the Bamboo docs and spec dump and doesn't
        // appear to be sensible to override.
        DockerRunContainerTask docker = new DockerRunContainerTask().imageName(this.image)
                .description(this.description)
                .serviceURLPattern("http://localhost:${docker.port}");

        // legacy options
        if (this.command != null) docker.containerCommand(this.command);
        if (this.workingDirectory != null) docker.containerWorkingDirectory(this.workingDirectory);
        // end legacy

        if (this.container != null) {
            this.container.applyConfig(docker);
        }

        if (this.detach) {
            if (this.container == null || this.container.name == null) {
                throw new RuntimeException("DOCKER detached tasks require 'container' -> 'name' to be set");
            }
            docker.detachContainer(true);
            if (this.serviceStartCheck != null) {
                this.serviceStartCheck.applyConfig(docker);
            }
        }

        if (this.environmentVariables != null) docker.environmentVariables(this.environmentVariables);

        if (this.cmdLineArguments != null) docker.additionalArguments(this.cmdLineArguments);

        docker.clearPortMappings();
        if (this.portMappings != null) {
            for (PortMapping port : this.portMappings) {
                docker.appendPortMapping(port.local, port.container);
            }
        }

        docker.clearVolumeMappings();
        if (this.volumeMappings != null) {
            for (VolumeMapping volume : this.volumeMappings) {
                docker.appendVolumeMapping(volume.local, volume.container);
            }
        }
        return docker;
    }
}
