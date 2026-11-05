package me.clama.api.identidade;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {
    @Query("select u from Usuario u where lower(u.email) = lower(?1)")
    List<Usuario> buscarPorEmailIgnorandoCaixa(String email);
}
