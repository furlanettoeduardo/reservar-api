package io.github.furlanettoeduardo.reservas.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Cliente que faz reservas. Sem framework, como {@link Espaco} -- o mapeamento vive em
 * {@code repository.jpa.ClienteJpa}.
 */
public final class Cliente {

    private final Long id;
    private final String nome;
    private final String email;
    private final Instant criadoEm;

    public Cliente(Long id, String nome, String email, Instant criadoEm) {
        this.id = id;
        this.nome = nome;
        this.email = email;
        this.criadoEm = criadoEm;
    }

    public Cliente(String nome, String email) {
        this(null, nome, email, null);
    }

    public Long getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public String getEmail() {
        return email;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Cliente outro = (Cliente) o;
        return Objects.equals(id, outro.id)
                && Objects.equals(nome, outro.nome)
                && Objects.equals(email, outro.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, nome, email);
    }

    @Override
    public String toString() {
        return "Cliente{id=%d, nome='%s', email='%s'}".formatted(id, nome, email);
    }
}
