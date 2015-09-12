package com.github.to2mbn.jmccc.launch;

import com.github.to2mbn.jmccc.exec.ProcessMonitor;

/**
 * The result of launching.
 * <p>
 * This will be returned only when the game is launched successfully.
 */
public class LaunchResult {

    private ProcessMonitor monitor;
    private Process process;

    public LaunchResult(ProcessMonitor monitor, Process process) {
        this.monitor = monitor;
        this.process = process;
    }

    /**
     * Gets the game process monitor
     * 
     * @return the game monitor
     */
    public ProcessMonitor getMonitor() {
        return monitor;
    }

    /**
     * Gets the game process
     * 
     * @return the game process
     */
    public Process getProcess() {
        return process;
    }


}