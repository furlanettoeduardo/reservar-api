package io.github.furlanettoeduardo.reservas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * Cliente que faz reservas. Mapeia a tabela {@code cliente}.
 *
 * <p>Mesmas decisoes da {@link Espaco}: classe mutavel nao-final para dirty checking e proxy,
 * construtor sem-args protected para o Hibernate, {@code criado_em} gerado pelo banco.
 */
@Entity
@Table(name = "cliente")
public class Cliente {

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

    protected Cliente() {
    }

    public Cliente(String nome, String email) {
        this.nome = nome;
        this.email = email;
    }

    public Long getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Cliente outro)) {
            return false;
        }
        return id != null && id.equals(outro.getId());
    }

    @Override
    public int hashCode() {
        return Cliente.class.hashCode();
    }

    @Override
    public String toString() {
        return "Cliente{id=%d, nome='%s', email='%s'}".formatted(id, nome, email);
    }
}
