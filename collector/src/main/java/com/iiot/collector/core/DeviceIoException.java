package com.iiot.collector.core;

/**
 * 驱动层统一抛这个 —— 上层（采样器 / 平台）只需要知道「这次没读到」，
 * 不需要知道底下是 ConnectException 还是 ModbusIOException。
 */
public class DeviceIoException extends Exception {

    public DeviceIoException(String message) {
        super(message);
    }

    public DeviceIoException(String message, Throwable cause) {
        super(message, cause);
    }
}
