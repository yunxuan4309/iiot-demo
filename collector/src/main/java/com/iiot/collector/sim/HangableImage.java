package com.iiot.collector.sim;

import com.ghgande.j2mod.modbus.procimg.DigitalIn;
import com.ghgande.j2mod.modbus.procimg.DigitalOut;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.ghgande.j2mod.modbus.procimg.SimpleProcessImage;

/**
 * 可以「卡死」或「延迟应答」的 process image —— 用来复现现场最难缠的一类故障。
 *
 * <h2>它解决什么问题</h2>
 * 之前的断线演练只能演「设备彻底没了」（TCP 连不上 / 拒绝连接），
 * 那种情况客户端**立刻**就知道失败。但现场更常见、更难查的是：
 *
 * <pre>
 *   连接建立成功 → 请求发出去了 → 设备就是不回包 → 客户端只能等到超时
 * </pre>
 *
 * 两种故障的差别很关键：
 * <table border="1">
 *   <tr><th></th><th>连接被拒</th><th>连上但不回包（本类）</th></tr>
 *   <tr><td>客户端感知</td><td><b>立刻</b>报错</td><td><b>卡满整个超时</b>才报错</td></tr>
 *   <tr><td>现场表现</td><td>设备断电/网线断</td><td>设备死机 / 网卡半死 / PLC 扫描周期被堵死</td></tr>
 *   <tr><td>危害</td><td>低（快速失败）</td><td><b>高</b> —— 一个设备能拖慢整轮采集</td></tr>
 * </table>
 *
 * <p>所以「单台挂死会不会拖垮其他台」必须单独验证 —— 这正是坑 5 的最后一块。
 *
 * <h2>两个开关</h2>
 * <ul>
 *   <li>{@link #setHanging(boolean)}：读请求进来后<b>不返回</b>（线程一直等），
 *       客户端只能超时。用来演「设备死机但 TCP 连接还在」。</li>
 *   <li>{@link #setWriteAckDelayMillis(long)}：写请求<b>照常执行</b>，但<b>把应答延迟</b>
 *       到超过客户端超时。用来演「写成功了，但响应丢了」—— 这是坑 4（写不幂等）的根源。</li>
 * </ul>
 *
 * <h2>为什么 override 的是 process image 而不是网络层</h2>
 * j2mod 的请求处理线程会调 {@code getRegisterRange()} 等方法拿数据再组包。
 * 在这些方法里卡住，<b>报文就永远组不出来</b> —— 效果等同"设备不响应"，
 * 但不用碰 TCP 层，也不会影响同一端口上其他 unitId 的连接。
 */
public final class HangableImage extends SimpleProcessImage {

    /** 卡住时每轮睡多久（用来及时响应"解除卡死"）。 */
    private static final long HANG_POLL_MILLIS = 200;

    private volatile boolean hanging;
    private volatile long writeAckDelayMillis;

    public HangableImage(int unitId) {
        super(unitId);
    }

    /** 开启/解除「卡死」。解除后挂住的请求线程会继续返回（模拟设备恢复）。 */
    public void setHanging(boolean hanging) {
        this.hanging = hanging;
    }

    public boolean isHanging() {
        return hanging;
    }

    /** 写请求执行后延迟多久才回包（超过客户端读超时 = 客户端会判定失败，但设备已经动了）。 */
    public void setWriteAckDelayMillis(long millis) {
        this.writeAckDelayMillis = millis;
    }

    public long writeAckDelayMillis() {
        return writeAckDelayMillis;
    }

    /** 卡死时阻塞当前请求线程 —— 报文永远组不出来，客户端只能超时。 */
    private void blockIfHanging() {
        while (hanging) {
            try {
                Thread.sleep(HANG_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ── 读：先看是否卡死，再走正常逻辑 ──

    @Override
    public Register getRegister(int ref) {
        blockIfHanging();
        return super.getRegister(ref);
    }

    @Override
    public Register[] getRegisterRange(int ref, int count) {
        blockIfHanging();
        return super.getRegisterRange(ref, count);
    }

    @Override
    public DigitalOut getDigitalOut(int ref) {
        blockIfHanging();
        return super.getDigitalOut(ref);
    }

    @Override
    public DigitalOut[] getDigitalOutRange(int ref, int count) {
        blockIfHanging();
        return super.getDigitalOutRange(ref, count);
    }

    @Override
    public DigitalIn getDigitalIn(int ref) {
        blockIfHanging();
        return super.getDigitalIn(ref);
    }

    @Override
    public DigitalIn[] getDigitalInRange(int ref, int count) {
        blockIfHanging();
        return super.getDigitalInRange(ref, count);
    }

    // ── 写：先【真的写进去】，再延迟回包 ──
    //
    // 顺序很重要：先写入再延迟，才叫"设备已经动了但客户端以为失败"。
    // 如果先延迟再写，就变成"客户端超时、设备没动"—— 那反而安全，不叫坑。

    @Override
    public void setRegister(int ref, Register reg) {
        super.setRegister(ref, reg);
        delayWriteAck();
    }

    @Override
    public void setDigitalOut(int ref, DigitalOut out) {
        super.setDigitalOut(ref, out);
        delayWriteAck();
    }

    private void delayWriteAck() {
        long delay = writeAckDelayMillis;
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
