import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Porta fixa em 5173 porque e a origem liberada em CorsDeDesenvolvimento.
// strictPort para falhar alto se a porta estiver ocupada, em vez de subir em outra
// e bater em CORS com uma mensagem que nao explica nada.
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, strictPort: true },
})
