package top.pmckk.flan.model;

import top.pmckk.flan.config.MessageException;

import java.util.UUID;

public record PlayerAccount(UUID id, String name, long allowance, long accrualSeconds) {
    public PlayerAccount {
        if (allowance < 0 || accrualSeconds < 0) throw new MessageException("errors.negative-account");
    }
    public PlayerAccount withAllowance(long value) { return new PlayerAccount(id, name, value, accrualSeconds); }
    public PlayerAccount renamed(String value) { return new PlayerAccount(id, value, allowance, accrualSeconds); }
}
