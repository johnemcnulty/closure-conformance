package com.example.closure.suite;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a fixture class as gated on fixture 1 (suite-design §6). It carries the gate for both
 * engines, and each ignores the other's. Every fixture but fixture 1 carries it.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(ProfileGate.JupiterCondition.class)
@AddLifecycleHook(ProfileGate.JqwikHook.class)
public @interface Fixture {
}
