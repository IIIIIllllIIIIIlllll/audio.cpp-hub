package org.mark.audiocpp.hub.instance;

import java.nio.file.Path;
import java.time.Instant;

/** 一个 audiocpp_server 进程实例。只有 STARTING/READY 的实例存在于管理器中。 */
public class ModelInstance {

    public enum Status { STARTING, READY }

    private final String id;
    private final String instanceName;
    private final String modelId;
    private final String weightsPath;
    private final int port;
    private final String backend;
    private final Integer device;
    private final String executableName;
    private final Path serverJsonPath;
    private final Instant createdAt = Instant.now();

    private volatile Status status = Status.STARTING;
    private volatile Process process;

    public ModelInstance(String id, String instanceName, String modelId, String weightsPath, int port,
                         String backend, Integer device, String executableName, Path serverJsonPath) {
        this.id = id;
        this.instanceName = instanceName;
        this.modelId = modelId;
        this.weightsPath = weightsPath;
        this.port = port;
        this.backend = backend;
        this.device = device;
        this.executableName = executableName;
        this.serverJsonPath = serverJsonPath;
    }

    public String getId() { return id; }
    /** 服务名：/v1/* 路由键，同时是实例 server.json 里的 model id，全局唯一。 */
    public String getInstanceName() { return instanceName; }
    public String getModelId() { return modelId; }
    public String getWeightsPath() { return weightsPath; }
    public int getPort() { return port; }
    public String getBackend() { return backend; }
    public Integer getDevice() { return device; }
    public String getExecutableName() { return executableName; }
    public Path getServerJsonPath() { return serverJsonPath; }
    public Instant getCreatedAt() { return createdAt; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Process getProcess() { return process; }
    public void setProcess(Process process) { this.process = process; }
}
