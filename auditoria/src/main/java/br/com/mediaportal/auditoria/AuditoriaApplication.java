package br.com.mediaportal.auditoria;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.CommandLineRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;

@SpringBootApplication
public class AuditoriaApplication implements CommandLineRunner {

	private int storageNivel = -1;

	public static void main(String[] args) {
		SpringApplication.run(AuditoriaApplication.class, args);
	}

	@Override
	public void run(String... args) {
		if (args.length < 2) {
			System.out.println("❗ Uso: java -jar auditoria.jar <caminho_arquivo_dir> <storageid>");
			return;
		}

		String caminhoArquivoDir = args[0];
		int storageId;
		try {
			storageId = Integer.parseInt(args[1]);
		} catch (NumberFormatException e) {
			System.out.println("❌ storageid precisa ser um número inteiro.");
			return;
		}

		storageNivel = obterNivelDoStorage(storageId);

		List<String> arquivosDir = lerArquivoOriginal(caminhoArquivoDir);
		List<String> arquivosBanco = buscarDoBanco(storageId);

		imprimirLista("🗂 Todos os arquivos do diretório", arquivosDir);
		imprimirLista("🗄 Todos os arquivos vindos do banco (Nível " + storageNivel + ")", arquivosBanco);

		ResultadosComparacao resultado = compararListas(arquivosDir, arquivosBanco);

		imprimirLista("📁 Arquivos no diretório, mas não no banco", resultado.soNoDiretorio);
		imprimirLista("💾 Arquivos no banco, mas não no diretório", resultado.soNoBanco);
		imprimirLista("✅ Arquivos sincronizados (em ambos)", resultado.sincronizados);

		System.out.print("\n⏳ Processando julgamentos");
		Thread animacao = new Thread(() -> {
			try {
				String[] spinner = {"|", "/", "-", "\\"};
				int i = 0;
				while (!Thread.currentThread().isInterrupted()) {
					System.out.print("\r⏳ Processando relatórios " + spinner[i++ % spinner.length]);
					Thread.sleep(200);
				}
			} catch (InterruptedException ignored) {}
		});
		animacao.start();

		julgarArquivos(resultado.sincronizados, arquivosDir, resultado.soNoBanco, storageNivel);

		animacao.interrupt();
		System.out.print("\r⏳ Processamento finalizado.            \n");

		System.out.println("\n✅ Auditoria concluída com sucesso!");
	}

	private int obterNivelDoStorage(int storageId) {
		String url = "jdbc:informix-sqli://192.168.15.130:9088/mediaxp:INFORMIXSERVER=ol_database";
		String usuario = "informix";
		String senha = "I1n2f3o4";

		String sql = "SELECT storagetype FROM mc_storage WHERE storageid = " + storageId;

		try (Connection conexao = DriverManager.getConnection(url, usuario, senha);
			 Statement stmt = conexao.createStatement();
			 ResultSet rs = stmt.executeQuery(sql)) {

			if (rs.next()) {
				int tipo = rs.getInt("storagetype");
				return tipo % 10;
			}

		} catch (SQLException e) {
			System.out.println("❌ Erro ao obter o nível do storage: " + e.getMessage());
		}

		return -1;
	}

	private List<String> lerArquivoOriginal(String caminhoArquivo) {
		List<String> linhas = new ArrayList<>();
		try {
			List<String> todasLinhas = Files.readAllLines(Paths.get(caminhoArquivo));
			for (String linha : todasLinhas) {
				if (linha != null && !linha.trim().isEmpty()) {
					String l = linha.trim();
					if (
							l.startsWith("Diretório:") ||
									l.startsWith("Mode") ||
									l.startsWith("----") ||
									l.startsWith("total")
					) {
						continue;
					}
					linhas.add(linha);
				}
			}
		} catch (IOException e) {
			System.out.println("❌ Erro ao ler o arquivo: " + e.getMessage());
		}
		return linhas;
	}

	private List<String> buscarDoBanco(int storageId) {
		String url = "jdbc:informix-sqli://192.168.15.130:9088/mediaxp:INFORMIXSERVER=ol_database";
		String usuario = "informix";
		String senha = "I1n2f3o4";

		List<String> resultados = new ArrayList<>();

		String sql = "SELECT FIRST 2 locationid, location.lo_location, timestamp::VARCHAR(32)::VARCHAR(10) as timestamp, " +
				"proxysize, onlinestatus FROM mc_assetlocation WHERE storageid = " + storageId;

		try (Connection conexao = DriverManager.getConnection(url, usuario, senha);
			 Statement stmt = conexao.createStatement();
			 ResultSet rs = stmt.executeQuery(sql)) {

			while (rs.next()) {
				String locationid = rs.getString("locationid");
				String location = rs.getString("lo_location");
				String timestamp = rs.getString("timestamp");
				String proxysize = rs.getString("proxysize");
				String onlinestatus = rs.getString("onlinestatus");

				String linha = "LocationID: " + locationid +
						" | Location: " + location +
						" | Timestamp: " + timestamp +
						" | Proxysize: " + proxysize +
						" | Onlinestatus: " + onlinestatus;

				resultados.add(linha);
			}

		} catch (SQLException e) {
			System.out.println("❌ Erro ao consultar o banco: " + e.getMessage());
		}

		return resultados;
	}

	private void imprimirLista(String titulo, List<String> lista) {
		System.out.println("\n" + titulo + ":");
		if (lista.isEmpty()) {
			System.out.println(" (Nenhum arquivo)");
		} else {
			for (int i = 0; i < lista.size(); i++) {
				System.out.println("Id: " + i + " " + lista.get(i));
			}
		}
	}

	private ResultadosComparacao compararListas(List<String> listaDir, List<String> listaBanco) {
		Set<String> nomesDir = new HashSet<>();
		for (String linha : listaDir) {
			String[] partes = linha.trim().split("\\s+");
			if (partes.length > 0) {
				nomesDir.add(partes[partes.length - 1]);
			}
		}

		Set<String> nomesBanco = new HashSet<>();
		for (String linhaBanco : listaBanco) {
			String location = extrairLocation(linhaBanco);
			if (location != null) {
				nomesBanco.add(location);
			}
		}

		List<String> soNoDiretorio = new ArrayList<>();
		List<String> soNoBanco = new ArrayList<>();
		List<String> sincronizados = new ArrayList<>();

		for (String linhaDir : listaDir) {
			String[] partes = linhaDir.trim().split("\\s+");
			String nomeArquivo = partes[partes.length - 1];
			if (!nomesBanco.contains(nomeArquivo)) {
				soNoDiretorio.add(linhaDir);
			}
		}

		for (String linhaBanco : listaBanco) {
			String location = extrairLocation(linhaBanco);
			if (location != null && !nomesDir.contains(location)) {
				soNoBanco.add(linhaBanco);
			}
		}

		for (String linhaBanco : listaBanco) {
			String location = extrairLocation(linhaBanco);
			if (location != null && nomesDir.contains(location)) {
				sincronizados.add(linhaBanco);
			}
		}

		return new ResultadosComparacao(soNoDiretorio, soNoBanco, sincronizados);
	}

	private String extrairLocation(String linhaBanco) {
		if (linhaBanco.contains("Location: ")) {
			String[] partes = linhaBanco.split("Location: ");
			if (partes.length > 1) {
				String[] depois = partes[1].split(" \\| ");
				return depois[0];
			}
		}
		return null;
	}

	private String extrairCampo(String linha, String campo) {
		String[] partes = linha.split("\\|");
		for (String parte : partes) {
			if (parte.trim().startsWith(campo + ":")) {
				return parte.split(":", 2)[1].trim();
			}
		}
		return "";
	}

	private void julgarArquivos(List<String> sincronizados, List<String> arquivosDir, List<String> soNoBanco, int storageNivel) {
		List<String> itensIgnorados = new ArrayList<>();
		List<String> itensArquivar = new ArrayList<>();
		List<String> itensPerdidos = new ArrayList<>();
		List<String> itensCorrigidos = new ArrayList<>();

		Set<String> nomesDiretorio = new HashSet<>();
		for (String linhaDir : arquivosDir) {
			String[] partes = linhaDir.trim().split("\\s+");
			if (partes.length > 0) {
				nomesDiretorio.add(partes[partes.length - 1]);
			}
		}

		// Verificar arquivos sincronizados (presentes no diretório e no banco)
		for (String linhaBanco : sincronizados) {
			String location = extrairLocation(linhaBanco);
			String onlinestatus = extrairCampo(linhaBanco, "Onlinestatus");
			String locationId = extrairCampo(linhaBanco, "LocationID");

			if ("0".equals(onlinestatus) && nomesDiretorio.contains(location)) {
				itensIgnorados.add(linhaBanco);
			} else if ("2".equals(onlinestatus) && storageNivel == 2 && nomesDiretorio.contains(location)) {
				// Caso 2 e 4: N2 com onlinestatus=2, está no diretório, verificar N3
				Integer n3Status = obterStatusNivelSuperior(locationId);
				if (n3Status != null && n3Status == 1) {
					// Corrigir N2: onlinestatus de 2 para 3
					if (corrigirOnlinestatus(locationId, 2, 3)) {
						itensCorrigidos.add(linhaBanco + " | Corrigido de onlinestatus 2 para 3: arquivo presente no diretório e N3 online");
					}
				}
			}
		}

		// Verificar arquivos só no banco
		for (String linhaBanco : soNoBanco) {
			String onlinestatus = extrairCampo(linhaBanco, "Onlinestatus");
			String locationId = extrairCampo(linhaBanco, "LocationID");
			String location = extrairLocation(linhaBanco);

			if ("1".equals(onlinestatus)) {
				System.out.println("📌 Avaliando arquivo em N" + storageNivel + ": LocationID: " + locationId + ", Location: " + location);
				if (storageNivel == 3) {
					if (!possuiCopiaEmNivelInferior(locationId)) {
						System.out.println("📌 Arquivo perdido em N3: LocationID: " + locationId + " (sem cópia em N1 ou N2)");
						itensPerdidos.add(linhaBanco);
					} else {
						System.out.println("📌 Arquivo para arquivar em N3: LocationID: " + locationId);
						itensArquivar.add(linhaBanco);
					}
				} else if (storageNivel == 2) {
					Integer n1Status = obterStatusNivelAnterior(locationId);
					boolean semNivelAnterior = naoPossuiNivelAnterior(locationId);
					boolean nivelAnteriorOffline = (n1Status == null || n1Status == 0 || n1Status == 2);

					if (semNivelAnterior || nivelAnteriorOffline) {
						System.out.println("📌 Arquivo perdido em N2: LocationID: " + locationId + " (sem N1 ou N1 offline)");
						itensPerdidos.add(linhaBanco);
					} else if (n1Status == 3 && !nomesDiretorio.contains(location)) {
						// Caso 1: N2 com onlinestatus=1, não está no diretório, N1 com onlinestatus=3
						String n1LocationId = obterN1LocationId(locationId);
						if (n1LocationId != null) {
							// Corrigir N2: onlinestatus de 1 para 0
							if (corrigirOnlinestatus(locationId, 1, 0)) {
								itensCorrigidos.add(linhaBanco + " | Corrigido de onlinestatus 1 para 0: arquivo não está no diretório");
							}
							// Corrigir N1: onlinestatus de 3 para 1
							String n1Linha = buscarLinhaN1(n1LocationId);
							if (n1Linha != null && corrigirOnlinestatus(n1LocationId, 3, 1)) {
								itensCorrigidos.add(n1Linha + " | Corrigido de onlinestatus 3 para 1: N1 correspondente a N2 corrigido");
							}
						}
					} else {
						System.out.println("📌 Arquivo não perdido em N2: LocationID: " + locationId + " (N1 online)");
					}
				} else if (storageNivel == 1) {
					if (!nomesDiretorio.contains(location)) {
						System.out.println("📌 Arquivo perdido em N1: LocationID: " + locationId);
						itensPerdidos.add(linhaBanco);
					}
				}
			} else if ("3".equals(onlinestatus) && storageNivel == 2 && !nomesDiretorio.contains(location)) {
				// Caso 3 e 5: N2 com onlinestatus=3, não está no diretório
				System.out.println("📌 Avaliando correção em N2: LocationID: " + locationId + ", Location: " + location);
				if (corrigirOnlinestatus(locationId, 3, 2)) {
					itensCorrigidos.add(linhaBanco + " | Corrigido de onlinestatus 3 para 2: arquivo não está no diretório");
				}
			}
		}

		imprimirLista("🚫 Relatório de Itens Ignorados", itensIgnorados);
		imprimirLista("📤 Necessário acionar arquivamento", itensArquivar);
		imprimirLista("📉 Arquivos Perdidos", itensPerdidos);
		imprimirLista("📝 Relatório de Itens Corrigidos", itensCorrigidos);
	}

	private boolean corrigirOnlinestatus(String locationId, int statusAntigo, int statusNovo) {
		String url = "jdbc:informix-sqli://192.168.15.130:9088/mediaxp:INFORMIXSERVER=ol_database";
		String usuario = "informix";
		String senha = "I1n2f3o4";

		if (locationId == null) {
			System.out.println("❌ LocationID é null em corrigirOnlinestatus");
			return false;
		}

		locationId = locationId.trim();

		if (!locationId.matches("\\d+")) {
			System.out.println("❌ LocationID inválido em corrigirOnlinestatus: [" + locationId + "]");
			return false;
		}

		String sql = "UPDATE mc_assetlocation SET onlinestatus = ? WHERE locationid = ? AND onlinestatus = ?";

		try (Connection conexao = DriverManager.getConnection(url, usuario, senha);
			 PreparedStatement stmt = conexao.prepareStatement(sql)) {

			stmt.setInt(1, statusNovo);
			stmt.setInt(2, Integer.parseInt(locationId));
			stmt.setInt(3, statusAntigo);
			int rowsAffected = stmt.executeUpdate();

			if (rowsAffected > 0) {
				System.out.println("📌 Onlinestatus atualizado de " + statusAntigo + " para " + statusNovo + " para LocationID: " + locationId);
				return true;
			} else {
				System.out.println("❌ Nenhuma linha atualizada para LocationID: " + locationId + " (onlinestatus não era " + statusAntigo + ")");
				return false;
			}
		} catch (SQLException e) {
			System.out.println("❌ Erro ao atualizar onlinestatus para LocationID: " + locationId + " - " + e.getMessage());
			return false;
		}
	}

	private Integer obterStatusNivelAnterior(String locationId) {
		String url = "jdbc:informix-sqli://192.168.15.130:9088/mediaxp:INFORMIXSERVER=ol_database";
		String usuario = "informix";
		String senha = "I1n2f3o4";

		if (locationId == null) {
			System.out.println("❌ LocationID é null em obterStatusNivelAnterior");
			return null;
		}

		locationId = locationId.trim();

		if (!locationId.matches("\\d+")) {
			System.out.println("❌ LocationID inválido em obterStatusNivelAnterior: [" + locationId + "]");
			return null;
		}

		String sql = "SELECT onlinestatus FROM mc_assetlocation WHERE archivelocationid = ?";

		try (Connection conexao = DriverManager.getConnection(url, usuario, senha);
			 PreparedStatement stmt = conexao.prepareStatement(sql)) {

			stmt.setInt(1, Integer.parseInt(locationId));
			ResultSet rs = stmt.executeQuery();

			if (rs.next()) {
				return rs.getInt("onlinestatus");
			} else {
				System.out.println("📌 Nenhum N1 encontrado para LocationID: " + locationId);
				return null;
			}
		} catch (SQLException e) {
			System.out.println("❌ Erro ao verificar status do nível anterior para LocationID: " + locationId + " - " + e.getMessage());
			return null;
		}
	}

	private Integer obterStatusNivelSuperior(String locationId) {
		String url = "jdbc:informix-sqli://192.168.15.130:9088/mediaxp:INFORMIXSERVER=ol_database";
		String usuario = "informix";
		String senha = "I1n2f3o4";

		if (locationId == null) {
			System.out.println("❌ LocationID é null em obterStatusNivelSuperior");
			return null;
		}

		locationId = locationId.trim();

		if (!locationId.matches("\\d+")) {
			System.out.println("❌ LocationID inválido em obterStatusNivelSuperior: [" + locationId + "]");
			return null;
		}

		String sql = "SELECT onlinestatus FROM mc_assetlocation WHERE locationid = (SELECT archivelocationid FROM mc_assetlocation WHERE locationid = ?)";

		try (Connection conexao = DriverManager.getConnection(url, usuario, senha);
			 PreparedStatement stmt = conexao.prepareStatement(sql)) {

			stmt.setInt(1, Integer.parseInt(locationId));
			ResultSet rs = stmt.executeQuery();

			if (rs.next()) {
				return rs.getInt("onlinestatus");
			} else {
				System.out.println("📌 Nenhum N3 encontrado para LocationID: " + locationId);
				return null;
			}
		} catch (SQLException e) {
			System.out.println("❌ Erro ao verificar status do nível superior para LocationID: " + locationId + " - " + e.getMessage());
			return null;
		}
	}

	private String obterN1LocationId(String locationId) {
		String url = "jdbc:informix-sqli://192.168.15.130:9088/mediaxp:INFORMIXSERVER=ol_database";
		String usuario = "informix";
		String senha = "I1n2f3o4";

		if (locationId == null) {
			System.out.println("❌ LocationID é null em obterN1LocationId");
			return null;
		}

		locationId = locationId.trim();

		if (!locationId.matches("\\d+")) {
			System.out.println("❌ LocationID inválido em obterN1LocationId: [" + locationId + "]");
			return null;
		}

		String sql = "SELECT locationid FROM mc_assetlocation WHERE archivelocationid = ?";

		try (Connection conexao = DriverManager.getConnection(url, usuario, senha);
			 PreparedStatement stmt = conexao.prepareStatement(sql)) {

			stmt.setInt(1, Integer.parseInt(locationId));
			ResultSet rs = stmt.executeQuery();

			if (rs.next()) {
				return rs.getString("locationid");
			} else {
				System.out.println("📌 Nenhum N1 encontrado para LocationID: " + locationId);
				return null;
			}
		} catch (SQLException e) {
			System.out.println("❌ Erro ao obter locationid do N1 para LocationID: " + locationId + " - " + e.getMessage());
			return null;
		}
	}

	private String buscarLinhaN1(String n1LocationId) {
		String url = "jdbc:informix-sqli://192.168.15.130:9088/mediaxp:INFORMIXSERVER=ol_database";
		String usuario = "informix";
		String senha = "I1n2f3o4";

		if (n1LocationId == null) {
			System.out.println("❌ N1 LocationID é null em buscarLinhaN1");
			return null;
		}

		n1LocationId = n1LocationId.trim();

		if (!n1LocationId.matches("\\d+")) {
			System.out.println("❌ N1 LocationID inválido em buscarLinhaN1: [" + n1LocationId + "]");
			return null;
		}

		String sql = "SELECT locationid, location.lo_location, timestamp::VARCHAR(32)::VARCHAR(10) as timestamp, " +
				"proxysize, onlinestatus FROM mc_assetlocation WHERE locationid = ?";

		try (Connection conexao = DriverManager.getConnection(url, usuario, senha);
			 PreparedStatement stmt = conexao.prepareStatement(sql)) {

			stmt.setInt(1, Integer.parseInt(n1LocationId));
			ResultSet rs = stmt.executeQuery();

			if (rs.next()) {
				String locationid = rs.getString("locationid");
				String location = rs.getString("lo_location");
				String timestamp = rs.getString("timestamp");
				String proxysize = rs.getString("proxysize");
				String onlinestatus = rs.getString("onlinestatus");

				return "LocationID: " + locationid +
						" | Location: " + location +
						" | Timestamp: " + timestamp +
						" | Proxysize: " + proxysize +
						" | Onlinestatus: " + onlinestatus;
			} else {
				System.out.println("📌 Nenhum registro encontrado para N1 LocationID: " + n1LocationId);
				return null;
			}
		} catch (SQLException e) {
			System.out.println("❌ Erro ao buscar linha do N1 para LocationID: " + n1LocationId + " - " + e.getMessage());
			return null;
		}
	}

	private boolean possuiCopiaEmNivelInferior(String locationId) {
		String url = "jdbc:informix-sqli://192.168.15.130:9088/mediaxp:INFORMIXSERVER=ol_database";
		String usuario = "informix";
		String senha = "I1n2f3o4";

		if (locationId == null) {
			System.out.println("❌ LocationID é null");
			return false;
		}

		locationId = locationId.trim();

		if (!locationId.matches("\\d+")) {
			System.out.println("❌ LocationID inválido: [" + locationId + "]");
			return false;
		}

		String sql = "SELECT m.storageid, s.storagetype, m.onlinestatus " +
				"FROM mc_assetlocation m " +
				"JOIN mc_storage s ON s.storageid = m.storageid " +
				"WHERE m.archivelocationid = ? " +
				"AND MOD(s.storagetype, 10) IN (1, 2) " +
				"AND m.onlinestatus IN (1, 3)";

		try (Connection conexao = DriverManager.getConnection(url, usuario, senha);
			 PreparedStatement stmt = conexao.prepareStatement(sql)) {

			stmt.setInt(1, Integer.parseInt(locationId));
			ResultSet rs = stmt.executeQuery();

			if (rs.next()) {
				System.out.println("📌 Cópia encontrada em nível inferior para LocationID: " + locationId + ", StorageID: " + rs.getInt("storageid") + ", Onlinestatus: " + rs.getInt("onlinestatus"));
				return true;
			} else {
				System.out.println("📌 Nenhuma cópia encontrada em nível inferior para LocationID: " + locationId);
			}
		} catch (SQLException e) {
			System.out.println("❌ Erro ao verificar cópias em níveis inferiores: " + e.getMessage());
			System.out.println("📌 SQL original: " + sql);
		}

		return false;
	}

	private boolean naoPossuiNivelAnterior(String locationId) {
		String url = "jdbc:informix-sqli://192.168.15.130:9088/mediaxp:INFORMIXSERVER=ol_database";
		String usuario = "informix";
		String senha = "I1n2f3o4";

		if (locationId == null) {
			System.out.println("❌ LocationID é null em naoPossuiNivelAnterior");
			return false;
		}

		locationId = locationId.trim();

		if (!locationId.matches("\\d+")) {
			System.out.println("❌ LocationID inválido em naoPossuiNivelAnterior: [" + locationId + "]");
			return false;
		}

		String sql = "SELECT 1 FROM mc_assetlocation WHERE archivelocationid = ?";

		try (Connection conexao = DriverManager.getConnection(url, usuario, senha);
			 PreparedStatement stmt = conexao.prepareStatement(sql)) {

			stmt.setInt(1, Integer.parseInt(locationId));
			ResultSet rs = stmt.executeQuery();

			boolean hasPreviousLevel = rs.next();
			System.out.println("📌 Verificando nível anterior para LocationID: " + locationId + " - Existe N1? " + hasPreviousLevel);
			return !hasPreviousLevel; // true se NÃO possui nível anterior
		} catch (SQLException e) {
			System.out.println("❌ Erro ao verificar nível anterior para LocationID: " + locationId + " - " + e.getMessage());
		}
		return false;
	}

	private boolean nivelAnteriorEstaOffline(String locationId) {
		String url = "jdbc:informix-sqli://192.168.15.130:9088/mediaxp:INFORMIXSERVER=ol_database";
		String usuario = "informix";
		String senha = "I1n2f3o4";

		if (locationId == null) {
			System.out.println("❌ LocationID é null em nivelAnteriorEstaOffline");
			return false;
		}

		locationId = locationId.trim();

		if (!locationId.matches("\\d+")) {
			System.out.println("❌ LocationID inválido em nivelAnteriorEstaOffline: [" + locationId + "]");
			return false;
		}

		String sql = "SELECT locationid, onlinestatus FROM mc_assetlocation WHERE archivelocationid = ?";

		try (Connection conexao = DriverManager.getConnection(url, usuario, senha);
			 PreparedStatement stmt = conexao.prepareStatement(sql)) {

			stmt.setInt(1, Integer.parseInt(locationId));
			ResultSet rs = stmt.executeQuery();

			if (rs.next()) {
				int status = rs.getInt("onlinestatus");
				String n1LocationId = rs.getString("locationid");
				System.out.println("📌 Verificando N1 para LocationID: " + locationId + " - N1 LocationID: " + n1LocationId + ", Onlinestatus: " + status);
				return (status == 0 || status == 2); // Offline se status = 0 ou 2
			} else {
				System.out.println("📌 Nenhum N1 encontrado para LocationID: " + locationId);
				return true; // Se não encontrou N1, considera offline
			}
		} catch (SQLException e) {
			System.out.println("❌ Erro ao verificar status do nível anterior para LocationID: " + locationId + " - " + e.getMessage());
		}

		return false;
	}

	private static class ResultadosComparacao {
		List<String> soNoDiretorio;
		List<String> soNoBanco;
		List<String> sincronizados;

		ResultadosComparacao(List<String> soNoDiretorio, List<String> soNoBanco, List<String> sincronizados) {
			this.soNoDiretorio = soNoDiretorio;
			this.soNoBanco = soNoBanco;
			this.sincronizados = sincronizados;
		}
	}
}