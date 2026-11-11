#!/usr/bin/env python3
"""Regenera os CSVs desta pasta com o csv.writer do Python, com as mesmas chamadas do `_gravar_csv` de
clama-backend/clama/instituicoes/management/commands/relatorio_repasse.py (HEAD): utf-8-sig, delimiter=";", newline="".
Eles sao a referencia byte a byte de PlanilhaCsvTest. Para auditar: `python3 gerar_fixtures.py` nesta pasta e `git diff`
nao pode mostrar nada. Os dados de cada arquivo tem de ser os do teste correspondente em PlanilhaCsvTest."""
import csv

PERIODO = "Período: 28/10/2026 a 27/11/2026"
CAB = ["Doador", "Referência do pedido", "Data da doação", "Valor doado (R$)", "Percentual de repasse", "Valor a receber (R$)"]
CAB_INT = ["Doador (nome completo)", "E-mail", "ID do pedido", "ID do pagamento no gateway", "Status do pedido", "Data do pedido",
           "Data da confirmação", "Valor no ledger (R$)", "Valor atual do pedido (R$)", "Percentual", "Repasse (R$)",
           "Status do repasse", "Transferido em", "Comprovante", "Divergência"]


def reais(c):  # _centavos_para_planilha
    return f"{c // 100},{c % 100:02d}"


def plural(n):  # _plural_doacao
    return "doação" if n == 1 else "doações"


def gravar(arquivo, instituicao, linhas, interna=False):
    with open(arquivo, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow([("AUDITORIA INTERNA de repasse — " if interna else "Relatório de repasse — ") + instituicao])
        w.writerow([PERIODO])
        if interna:
            w.writerow(["USO INTERNO — contém dado pessoal. Não entregar à instituição."])
        w.writerow([])
        w.writerow(CAB_INT if interna else CAB)
        for l in linhas:
            w.writerow(l)
        w.writerow([])
        n = len(linhas)
        if interna:
            doado = sum(int(l[7].replace(",", "")) for l in linhas)
            rep = sum(int(l[10].replace(",", "")) for l in linhas)
            w.writerow([f"TOTAL ({n} {plural(n)})", "", "", "", "", "", "", reais(doado), "", "", reais(rep), "", "", "", ""])
        else:
            doado = sum(int(l[3].replace(",", "")) for l in linhas)
            rep = sum(int(l[5].replace(",", "")) for l in linhas)
            w.writerow([f"TOTAL ({n} {plural(n)})", "", "", reais(doado), "", reais(rep)])


gravar("duas-doacoes.csv", "Lar; Esperança", [
    ["Maria S.", "#a3f2…8b1c", "28/10/2026", reais(5000), "20%", reais(1000)],
    ["José Á.", "#0001…ffff", "05/11/2026", reais(1234), "20%", reais(246)]])
gravar("uma-doacao.csv", 'Mar "de" Espanha', [["Ana", "#1111…2222", "27/11/2026", reais(3000), "20%", reais(600)]])
gravar("zerado.csv", "Pela Vida", [])
gravar("interna.csv", "Lar; Esperança", [
    ["Maria Silva Santos", "maria@exemplo.com", "a3f2c0de-0000-4000-8000-000000008b1c", "1325814423", "ENVIADA", "28/10/2026",
     "28/10/2026", reais(5000), reais(5000), "20%", reais(1000), "TRANSFERIDO", "02/11/2026", "PIX-E123; lote", ""],
    ["(sem nome)", "", "00010000-0000-4000-8000-00000000ffff", "(sem id no gateway)", "PAGO", "05/11/2026", "05/11/2026",
     reais(3000), reais(0), "20%", reais(600), "REGISTRADO", "", "", "pedido hoje vale 0 centavos, ledger registrou 3000"]], interna=True)
