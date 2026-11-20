package com.easyshell.server.workflow.engine;

/**
 * 节点被取消（run 级取消传播或节点自身超时）。引擎把节点置 cancelled 而不重试。
 */
public class NodeCancelledException extends RuntimeException {

    public NodeCancelledException(String message) {
        super(message);
    }
}
