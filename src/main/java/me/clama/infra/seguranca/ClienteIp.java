package me.clama.infra.seguranca;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Collections;

/** IP do cliente de melhor esforco (o XFF e forjavel; serve so de chave complementar de limite). */
public final class ClienteIp {

	private ClienteIp() {}

	/** Primeiro item do X-Forwarded-For que seja IP literal (normalizado), senao o remoteAddr. Nunca lanca. */
	public static String de(HttpServletRequest req) {
		var headers = req.getHeaders("X-Forwarded-For");
		if (headers != null) {
			for (var header : Collections.list(headers)) {
				for (var item : header.split(",")) {
					try {
						return InetAddress.ofLiteral(item.strip()).getHostAddress(); // so literal, nunca DNS
					} catch (IllegalArgumentException lixo) {
						// proximo item
					}
				}
			}
		}
		return req.getRemoteAddr();
	}
}
