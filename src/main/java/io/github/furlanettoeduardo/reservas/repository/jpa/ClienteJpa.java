package io.github.furlanettoeduardo.reservas.repository.jpa;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/** Mapeamento da tabela {@code cliente}. Ver {@link EspacoJpa} para o raciocinio. */
@Entity
@Table(name = "cliente")
public class ClienteJpa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nome", nullable = false, length = 150)
    private String nome;

    /** {@code unique = true} e documentacao: quem gera o DDL e a migration, nao o Hibernate. */
    @Column(name = "email", nullable = false, length = 255, unique = true)
    private String email;

    @Generated(event = EventType.INSERT)
    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    protected ClienteJpa() {
    }

    public static ClienteJpa de(Cliente cliente) {
        ClienteJpa jpa = new ClienteJpa();
        jpa.id = cliente.getId();
        jpa.aplicar(cliente);
        return jpa;
    }

    public void aplicar(Cliente cliente) {
        this.nome = cliente.getNome();
        this.email = cliente.getEmail();
    }

    public Cliente paraDominio() {
        return new Cliente(id, nome, email, criadoEm);
    }

    public Long getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClienteJpa outro)) {
            return false;
        }
        return id != null && id.equals(outro.getId());
    }

    @Override
    public int hashCode() {
        return ClienteJpa.class.hashCode();
    }

    @Override
    public String toString() {
        return "ClienteJpa{id=%d, email='%s'}".formatted(id, email);
    }
}
