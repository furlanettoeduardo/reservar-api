package io.github.furlanettoeduardo.reservas;

import org.springframework.boot.SpringApplication;

public class TestReservarApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(ReservarApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
