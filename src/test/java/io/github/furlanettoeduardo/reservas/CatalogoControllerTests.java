package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.service.CatalogoService;
import io.github.furlanettoeduardo.reservas.service.ClienteResponse;
import io.github.furlanettoeduardo.reservas.service.EspacoResponse;
import io.github.furlanettoeduardo.reservas.web.CatalogoController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fatia web dos endpoints de catalogo, que existem para a tela popular os selects. */
@WebMvcTest(CatalogoController.class)
class CatalogoControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogoService service;

    @Test
    void listaEspacosComPrecoEmEscalaDeCentavo() throws Exception {
        given(service.listarEspacos()).willReturn(List.of(
                new EspacoResponse(1L, "Sala Azul", 30, new BigDecimal("150.00")),
                new EspacoResponse(2L, "Sala Verde", 12, new BigDecimal("90.00"))));

        mockMvc.perform(get("/espacos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].nome").value("Sala Azul"))
                .andExpect(jsonPath("$[0].capacidade").value(30))
                .andExpect(jsonPath("$[0].precoHora").value(150.00));
    }

    @Test
    void listaClientes() throws Exception {
        given(service.listarClientes())
                .willReturn(List.of(new ClienteResponse(1L, "Ana Souza", "ana@exemplo.com")));

        mockMvc.perform(get("/clientes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nome").value("Ana Souza"))
                .andExpect(jsonPath("$[0].email").value("ana@exemplo.com"));
    }
}
