package com.ledgerline;

import org.springframework.boot.SpringApplication;

public class TestLedgerlineApplication {

	public static void main(String[] args) {
		SpringApplication.from(LedgerlineApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
