# Controle Financeiro para Motorista de App (Uber)

Aplicativo de console em **Java 17, sem dependências externas**, para o motorista saber se está realmente lucrando:
cadastro do carro validado contra regras da Uber, financiamento, custos, combustível, ganhos, relatórios e estratégias.
**Todos os dados ficam só no seu computador** (arquivos `.tsv` legíveis em `~/.driverfinance`). Nada é enviado para a internet.

> Projeto independente, sem vínculo com a Uber. Não é aconselhamento financeiro.

## Sobre a "base de dados da Uber" (leia isto)

A Uber **não oferece API nem base pública** com os carros aceitos. A lista oficial muda por cidade e por data e fica em
<https://www.uber.com/br/pt-br/drive/vehicle-requirements/>. Por isso o app usa um **catálogo em CSV editável**
(`src/main/resources/catalogo_veiculos.csv`) e um motor de regras:

- UberX / Comfort / Black: 4 portas, ar-condicionado, ano mínimo por modelo e por cidade;
- Black: cores aceitas (preto, cinza, prata, branco, azul-marinho, marrom, chumbo);
- exclusões com data (ex.: Logan fora do Comfort desde 06/07/2026);
- veículos com placa vermelha, adesivados, sinistrados ou com suspensão/freios alterados são recusados;
- requisitos do motorista: mais de 100 viagens e nota mínima (4,85 no Black; 4,80/4,85 no Comfort conforme a cidade).

**Os anos mínimos do catálogo incluído são valores de referência** (`conferido_uber=NAO`); apenas alguns itens citam
comunicados da Uber. Confira no site/app da Uber antes de comprar ou financiar e corrija o CSV.
Para usar seu próprio catálogo: *Configurações → Importar catálogo (CSV)* ou salve em `~/.driverfinance/catalogo.csv`
(tem prioridade sobre o embutido). O formato está documentado no cabeçalho do CSV.

## Como rodar

Requer JDK 17+.

```bash
# Maven
mvn package
java -jar target/uber-driver-finance-1.0.0.jar

# ou sem Maven
mkdir out
javac -encoding UTF-8 -d out $(find src/main/java -name '*.java')
cp -r src/main/resources/* out/
java -cp out br.com.driverfinance.App
```

No Windows, se acentos aparecerem errados: `chcp 65001` antes de executar.
Para mudar a pasta de dados: variável de ambiente `DRIVERFINANCE_HOME`.

## Recursos

| Menu | O que faz |
|---|---|
| Veículo | Busca o modelo no catálogo, valida ano/cor/portas/ar e **adiciona automaticamente as categorias aceitas**. Vários carros, odômetro e valor de mercado. |
| Financiamento | Parcelas, entrada, parcelas pagas/restantes, saldo devedor, custo total e **taxa de juros implícita (% a.m.)**. |
| Custos | Manutenção, óleo, pneus, freios, revisão, seguro, IPVA, licenciamento, lavagem, multas, pedágio etc. Únicos, mensais ou anuais (rateados por dia). |
| Abastecimento | Gasolina, etanol ou kWh. Calcula o **consumo real** pelo método do tanque cheio e avisa se estiver 15%+ pior que o declarado. |
| Dia de trabalho | Horas, km, corridas, ganhos e gorjetas, com resumo imediato (R$/h, R$/km, sobra após combustível). |
| Relatórios | Hoje, 7 dias, mês, 30 dias ou período livre. Lucro líquido (caixa) e **lucro real com depreciação**. Exporta CSV. |
| Combustível | Etanol × gasolina pela regra `preço_etanol/preço_gasolina < km/l_etanol ÷ km/l_gasolina`, custo por km e veredito: compensa, no limite ou não compensa (% do faturamento). |
| Estratégias | Piso de R$/km para aceitar corrida, melhores e piores dias da semana, ponto de equilíbrio, plano para a meta mensal, peso do financiamento, requisitos para subir de categoria. |
| Lembretes | Troca de óleo, pneus, freios e revisão por quilometragem. Intervalos são sugestões genéricas: siga o manual do carro. |

### Como os números são calculados

- Custos mensais viram `valor × dias / 30,4375` e anuais `valor × dias / 365`, a partir da data de início informada.
- Sem abastecimentos no período, o combustível é **estimado** (km × custo por km) e o relatório avisa.
- Depreciação: `valor de mercado × taxa anual × dias / 365` (padrão 10% a.a., configurável).
- Limites do `Advisor`: combustível acima de 30% do faturamento = atenção, acima de 40% = não compensa; parcela acima de 25% do faturamento mensal = risco alto (constantes em `Advisor.java`).

## Estrutura

```
src/main/java/br/com/driverfinance/
  App.java        menus de console
  Models.java     entidades (Vehicle, Financing, Expense, FuelEntry, WorkDay)
  Store.java      persistência local em .tsv, repositório genérico, configurações
  Catalog.java    catálogo de modelos + motor de elegibilidade
  Analytics.java  relatórios, consumo real, etanol x gasolina, manutenção
  Advisor.java    estratégias baseadas em regras
  Io.java         entrada/saída (pt-BR)
src/main/resources/catalogo_veiculos.csv
```

## Ideias para evoluir

Testes automatizados (JUnit), interface web/desktop, suporte a Uber Moto/Flash/Green, importação de extratos da Uber,
multi-plataforma (99, inDrive) com comparação, cálculo de imposto (MEI/INSS) e backup em nuvem opcional.

## Licença

MIT
