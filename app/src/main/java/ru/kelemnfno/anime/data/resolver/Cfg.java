package ru.kelemnfno.anime.data.resolver;

import android.util.Base64;

import java.nio.charset.StandardCharsets;

/**
 * Все адреса и домены хранятся зашифрованными (инверсия байтов + Base64)
 * и собираются в рантайме. Ключ лежит в нативной библиотеке — в dex его нет.
 */
public final class Cfg {

    private static final String[] T = {
            "Mi4uKilgdXU7KjN0Izs0M3QuLHU=",
            "Mi4uKilgdXUpLjsuMzl0Izs0M3QuLA==",
            "Mi4uKilgdXUpMjMxMzc1KDN0NTQ/",
            "Mi4uKilgdXUjOzQzdC4sdQ==",
            "Mi4uKilgdXU7NjY1Mjt0Izs0M3QuLA==",
            "Izs0M3QuLA==",
            "OT40dDcjOzQzNz82MykudDQ/LnUzNzs9Pyl1OzQzNz8=",
            "Mi4uKilgdXU7Nz50NTQ2MzQ/",
            "Mi4uKilgdXU7NDM4NTU3dDU0P3U=",
            "Mi4uKilgdXU7NDM2Mzh0Nz91",
            "Mi4uKilgdXU7NDM2MzgoMzt0LjUqdTsqM3Usa3U7NDM3P3U5Oy47NjU9dSg/Nj87KT8p",
            "Mi4uKilgdXU7NDM2MzgoMzt0LjUqdTsqM3Usa3U7NDM3P3UoPzY/Oyk/KXU=",
            "Mi4uKilgdXU7NDM3Pz01dDUoPXU=",
            "Mi4uKilgdXU7NDM3Py4xO3Q5NTc=",
            "Mi4uKilgdXU7NDM3Py4xO3Q5NTd1",
            "Mi4uKilgdXU7NDM3Py4xO3Q5NTd1OyozdTs0Mzc/dQ==",
            "Mi4uKilgdXU7NDM3Py4xO3Q5NTd1OyozdTs0Mzc/dSo2OyM2Myku",
            "Mi4uKilgdXU7NDM3Py4xO3Q5NTd1OyozdTs0Mzc/dSk/Oyg5Mg==",
            "Mi4uKilgdXU7KjN3KXQ7NDMiKT8xOzN0OTU3",
            "Mi4uKilgdXU7KjN0OzY2NSgzPTM0KXQtMzR1KDstZS8oNmc=",
            "Mi4uKilgdXU7KjN0OzQzNz8sNSkudDUoPXUsa3UqNjsjNjMpLg==",
            "Mi4uKilgdXU7KjN0OzQzNz8sNSkudDUoPXUsa3UpPzsoOTI=",
            "Mi4uKilgdXU7KjN0OzQzIik/MTszdDk1Nw==",
            "Mi4uKilgdXU7KjN0OT40NjM4KXQ1KD11OyozdTs0Mzc/",
            "Mi4uKilgdXU7KjN0OT40NjM4KXQ1KD11OyozdT8qMyk1Pj8pdQ==",
            "Mi4uKilgdXU7KjN0OT40NjM4KXQ1KD11OyozdT8qMyk1Pj8pZTs0Mzc/BTM+Zw==",
            "Mi4uKilgdXU7KjN0MDMxOzR0NzU/dSxudTs0Mzc/dQ==",
            "Mi4uKilgdXU7KjN0MDMxOzR0NzU/dSxudTs0Mzc/ZStn",
            "Mi4uKilgdXU7KjN0KTIzMTM3NSgzdDc/dTs0Mzc/KXU=",
            "Mi4uKilgdXU5NSgpKig1IiN0MzV1ZS8oNmc=",
            "Mi4uKilgdXU9KDsqMis2dDs0MzYzKS50OTV1",
            "Mi4uKilgdXUyOzQzNz90Liw=",
            "Mi4uKilgdXUyOzQzNz90Lix1",
            "Mi4uKilgdXUyOzQzNz90Lix1OyozdSxidSwzPj81ZTM+Zw==",
            "Mi4uKilgdXUxNT4zMSo2OyM/KHQ5NTd1PC41KA==",
            "Mi4uKilgdXU3Izs0Mzc/NjMpLnQ0Py4=",
            "Mi4uKilgdXU3Izs0Mzc/NjMpLnQ0Py51OzQzNz91",
            "Mi4uKilgdXU1NDYzND90OzQzPi84dDk1Nw==",
            "Mi4uKilgdXU1NDYzND90OzQzPi84dDk1N3U=",
            "Mi4uKilgdXUqNjsqM3Q5PjQsMz4/NTIvOHQ5NTd1OyozdSxrdSo2OyM/KHUpLHUqNjsjNjMpLmUqLzhnbW5vfDM+Zw==",
            "Mi4uKilgdXUqNjsqM3Q5PjQsMz4/NTIvOHQ5NTd1OyozdSxrdSo2OyM/KHUpLHUsMz4/NXU=",
            "Mi4uKilgdXUoL3QjLzc3Izs0M3Q3Pw==",
            "Mi4uKilgdXUoL3QjLzc3Izs0M3Q3P3U=",
            "Mi4uKilgdXUoLy4vOD90KC8=",
            "Mi4uKilgdXUoLy4vOD90KC91",
            "Mi4uKilgdXUoLy4vOD90KC91OyozdSo2OyN1NSouMzU0KXU=",
            "Mi4uKilgdXUpPzsoOTJ0Mi4sdyk/KCwzOT8pdDk1N3U=",
            "Mi4uKilgdXUpMjMxMzc1KDN0Nz91OzQzNz8pdQ==",
            "Mi4uKilgdXUpMjMxMzc1KDN0NTQ/dQ==",
            "Mi4uKilgdXUpMjMxMzc1KDN0NTQ/dTs0Mzc/KXU=",
            "Mi4uKilgdXUpMjMxMzc1KDN0NTQ/dTsqM3U7NDM3Pyl1",
            "Mi4uKilgdXUsMz4/NXQpMzg0Py50KC8=",
            "Mi4uKilgdXUsMz4/NXQpMzg0Py50KC91",
            "Mi4uKilgdXUsMXQ5NTc=",
            "KjY7Iz8odDsxKTUodC4s",
            "LDM+PzV0KTM4ND8udCgv",
            "LDM+PzVrdDs0MzYzOHQ3Pw==",
            "LDM+PzVodDs0MzYzOHQ3Pw==",
            "OzEpNSh0Liw=",
            "OzY2NTI7dC4s",
            "OzQzODU1N3Q1ND8=",
            "OzQzNjM4dDc/",
            "OzQzNz8uMTt0OTU3",
            "OzQzKzMudDk1Nw==",
            "OT40LDM+PzUyLzh0OTU3",
            "MjY7Nz8odCgv",
            "Mi4uKil/aRt/aBx/aBwpMjMxMzc1KDN0NTQ/f2gcOzQzNz8pf2gc",
            "MTU+MzF0OTk=",
            "MTU+MzF0MzQ8NQ==",
            "MTU+MzEqNjsjPyh0OTU3",
            "KC8uLzg/dCgv",
            "KTM4ND8udCgv",
            "KS41KDc1dC4s",
            "LDF0OTU3",
            "LDEsMz4/NXQ5NTc=",
            "LDEsMz4/NXQoLw==",
            "Iy83NyM7NDN0Nz8=",
            "ID8+PDM2N3QoLw==",
            "dTsqM3U7NDM3P3UqNjsjNjMpLg==",
            "dTsqM3UsMz4/NXU=",
            "dTc1LDM/dQ==",
            "GzQzFjM4KDM7dA4M",
            "MTU+MzF0ODMg",
            "LikvIy8=",
            "cjIuLiopZWAGBmV1BgZldSwxBnQ5NTd1LDM+PzUFPyIuBnQqMioBBHh9ZmQGKQdxcw==",
            "Mi4uKillYAYGZXUGBmV1LS0tBnQpLjUoNzUGdC4sdT0/LgU8MzY/dQEEeH1mZAYpB3FlBnQ3Km51ZQ==",
            "KC8uLzg/BnQoL3VyZWAqNjsjdT83OD8+JiwzPj81c3VyAWp3Yzt3PAchaWgncw==",
            "ejUoeg==",
            "eHZ4KT87KDkyGCN4YGon",
            "eHZ4Ljs9KXhgAQd2eC47PSkFNzU+P3hgeBsUHnh2",
            "eDs5LjMsP3gGKXBgBilwBiEGKXB4Mz54BilwYAYpcHIGPnFz",
            "eDgoOzQ+KXhgAQd2eDg2OzkxNjMpLnhgAQd2eDUoPj8oBTgjeGB4OSg/Oy4/PgU7LgUvNDMieHZ4NSg+PygzND14YHg+Pyk5eHZ4Kjs9P3hgaic=",
            "eR8CDncCdwkOCB8bF3cTFBw=",
            "eR8CDhdpDw==",
            "fDs9PShnNzs2Mw==",
            "fDs3KmE=",
            "fDssa2cuKC8/fDsvLjUqNjsjZ2p8Oy8+MzVnfCkvOC4zLjY/Zw==",
            "fDY7Ky81YQ==",
            "fDYzNzMuZ2hq",
            "fDc+OykyYQ==",
            "fDQ4KSph",
            "fDQ+OykyYQ==",
            "fCo2OyM2Myku",
            "fCosPyhnLGg=",
            "fCsvNS5h",
            "fCg7Ky81YQ==",
            "cmVgKSg5JjIoPzxzZwF4fQdyAQR4fQdwLDM+PzUFPyIuBnQqMioBBHh9B3FzAXh9Bw==",
            "cmVgLDM+PzUTNDw1JiwTNDw1cwZ0MjspMgYpcAZxZWcGKXABeH0HcnRxZXMBeH0H",
            "cmVgLDM+PzUTNDw1JiwTNDw1cwZ0Mz4GKXAGcWVnBilwAXh9B3J0cWVzAXh9Bw==",
            "cmVgLDM+PzUTNDw1JiwTNDw1cwZ0LiMqPwYpcAZxZWcGKXABeH0HcnRxZXMBeH0H",
            "cmUzc3wqNjsjNjMpLnRwfg==",
            "cmUzc2Y4KAYpcHVlZA==",
            "cmUzL3Nyi9uK5orki9iL2orvi9iL1iaK5IrniuGK6orjiucmiuqK54riiuaK7yaL24rvi9qK4orqiuFz",
            "cgFqd2M7dzwHIWloJ3M=",
            "cgY+IWl2bidzKg==",
            "dDdpL2I=",
            "dDcqPg==",
            "dWU0NQVuam5nLigvP3woPzw/KD8oZw==",
            "dT8qMyk1Pj91",
            "dTwuNSg=",
            "dT0/LncqNjsjPyg=",
            "dTM0Pj8idCoyKmU+NWcpPzsoOTI=",
            "dSk/Oyg5MnUoPzY/Oyk/KXVq",
            "dSwzPnQqMiplLGd1",
            "dSw1PnU=",
            "ZjsBBGQHcTIoPzxneHJyZWAyLi4qKWVgdXUBBHgHcXNldQEEeAdwZQY+cQEEeHUHcAZ0Mi43NnN4AQRkB3BkcgEGKQYJByFqdmxqaidlc2Z1O2Q=",
            "Zjc/LjsGKXE0Ozc/BilwZwYpcAF4fQcvKT8oAXh9BwYpcTk1NC4/NC4GKXBnBilwAXh9B3J0cWVzAXh9Bw==",
            "ZikqOzQBBGQHcT47LjtneHIBBHgHcXN4AQRkB3BkcgEGKQYJByFqdmxqJ2VzZnUpKjs0ZA==",
            "ZTQ/Pz4FLDM+PzUpZy4oLz8=",
            "Gzk5Pyou",
            "Gzk5PyoudxY7ND0vOz0/",
            "Gzk5PyouKXcZNTQuKDU2KQ==",
            "Gzc7IDM0PXoeLzg4MzQ9",
            "GzQzHg8Y",
            "GzQzHDM2Nw==",
            "GzQzFjM4KDM7ehk2OykpMzk=",
            "GzQzFjM4KDM7eh4vOA==",
            "GzQzFjM4KDM7ehIe",
            "GzQzFzsvNC4=",
            "GzQzFz8+Mzs=",
            "GzQzCjY7Iw==",
            "GzQzCDMpPw==",
            "GzQzCS47KHp8eh4fHwo=",
            "GzQzNz8MNSku",
            "GD8jNTQ+YAkuLz4zNQ==",
            "GTU0Lj80LncOIyo/",
            "GSgvNDkyIyg1NjY=",
            "Hig/Ozd6GTspLg==",
            "EBsX",
            "ETs0KTsz",
            "FD8uPDYzIg==",
            "FD8tCS47LjM1NA==",
            "FTQNOyw/",
            "FSgzPTM0",
            "CB8JFRYPDhMVFGcGPnEicgY+cXM=",
            "CDs0PT8=",
            "CD88Pyg/KA==",
            "CRITABt6Cig1MD85Lg==",
            "CS4vPjM1GDs0Pg==",
            "Dw4cd2I=",
            "Dyk/KHcbPT80Lg==",
            "DTsxOzQzNw==",
            "AncIPysvPykuPz53DTMuMg==",
            "AhcWEi4uKgg/Ky8/KS4=",
            "AXh9B3JlYDI2KQU8NypuJjI2KSY+OykyBSk/KnMBeH0HBilwAWBnBwYpcAF4fQdyAQR4fQdxcwF4fQc=",
            "AXh9B3JlYC8oNiY3Km4Fc3IGPiFpdm4ncwF4fQcGKXABYGcHBilwAXh9B3IBBHh9B3FzAXh9Bw==",
            "AXh9B3IGdSwGdQE7dyBqd2MHcQZ1AQR4fQdxZQZ0N2kvYnMBeH0H",
            "AXh9BzI7KTIBeH0HBilwYAYpcAF4fQdydHFlcwF4fQc=",
            "AXh9BzM+AXh9BwYpcGAGKXABeH0HcnRxZXMBeH0H",
            "AXh9By4jKj8BeH0HBilwYAYpcAF4fQdydHFlcwF4fQc=",
            "AWV8ByxnBnVlcgEEfAdxcw==",
            "AQY0BigH",
            "AQQbdwA7dyBqd2NxdWcH",
            "AQQGKiEWJwYqIRQnB3E=",
            "BnRyN2kvYiY3Km5z",
            "BnRyNypuJjcxLCYtPzg3cw==",
            "BnQ3Km5yBmUmfnM=",
            "Bh5x",
            "BjgwOzcGOA==",
            "BjgpLzgGOA==",
            "BjgvKDYKOyg7NykGKXBnBilweHIBBHgHcXN4",
            "BjgvKDYKOyg7NykGKXBnBilwfXIBBH0HcXN9",
            "BihlBjQ=",
            "BilwdQYpcA==",
            "BilwAZjtJgcGKXB+",
            "BilwBnIBBHMHcAZzBilw",
            "BilwBgEBBAYHB3AHBilw",
            "Bilx",
            "BHIyLi4qKWVgc2V1dXRw",
            "BAEGPnR2BilxB3A=",
            "BAY+IWt2aSdyBnQGPiFrdmkncyFpJ34=",
            "BDIuLiopZWB1dXRw",
            "OzEpNSh0",
            "OzYzOyk=",
            "OzY2NTI7dA==",
            "OzY2NTI7YHqK54rvi9h6iuWK6ovaiuqK5orvi9iL2orkiug=",
            "OzY2NTI7YHqK5YvZi9uL2Irk",
            "OzYuZ3hyAQR4B3FzeA==",
            "OzYuPyg0Oy4zLD8=",
            "Ozc7IDM0PQ==",
            "Ozc7IDM0PXc+Lzg4MzQ9",
            "OzQzej4vOA==",
            "OzQzejwzNjc=",
            "OzQzejc7LzQu",
            "OzQzejc/PjM7",
            "OzQzeio2OyM=",
            "OzQzeigzKT8=",
            "OzQzPi84",
            "OzQzPDM2Nw==",
            "OzQzNjM4PyguIw==",
            "OzQzNjM4KDM7",
            "OzQzNjM4KDM7dzk2OykpMzk=",
            "OzQzNjM4KDM7dz4vOA==",
            "OzQzNjM4KDM7dzI+",
            "OzQzNzsvNC4=",
            "OzQzNz96LDUpLg==",
            "OzQzNz91",
            "OzQzNz9lK2c=",
            "OzQzNz9lKTIzMTM3NSgzBTM+KWc=",
            "OzQzNz8FMz4=",
            "OzQzNz8+Mzs=",
            "OzQzNz82Mzg=",
            "OzQzNz82MzhuMQ==",
            "OzQzNz8pBnVyBj5xcw==",
            "OzQzNz8uMTs=",
            "OzQzNz8uMTtgeorli9mL24vYiuQ=",
            "OzQzNz8uMTsFMz4=",
            "OzQzNz8sNSku",
            "OzQzKjY7Iw==",
            "OzQzKzMu",
            "OzQzKDMpPw==",
            "OzQzKS47KA==",
            "OzQzKS47KHc+Pz8q",
            "OzQzIg==",
            "OzQzIno5NT4/",
            "OzQzIik/MTsz",
            "OyoqNjM5Oy4zNTR1MCk1NA==",
            "OyoqNjM5Oy4zNTR1MCk1NHZ6Lj8iLnUwOyw7KTkoMyoudnpwdXBheitnanRqaw==",
            "OyoqNjM5Oy4zNTR1MCk1NHZ6Lj8iLnUqNjszNHZ6cHVw",
            "OyoqNjM5Oy4zNTR1MCk1NHZwdXA=",
            "Oy41OAZyBilwAXh9B3IBG3cAO3cgandjcXVnB3FzAXh9Bw==",
            "Oy8uNQ==",
            "OywuNQ==",
            "ODs+BS8pPyg=",
            "OD8jNTQ+",
            "OD8jNTQ+dykuLz4zNQ==",
            "OCMuPylnandoam5i",
            "OTspLg==",
            "OT40BTMpBS01KDEzND0=",
            "OT40NjM4KQ==",
            "OT40LDM+PzUyLzg=",
            "OTY7KSkzOQ==",
            "OTU+Pw==",
            "OTU0KS4GKXE8MzY/FjMpLgYpcGcGKXAQCRUUBnQqOygpPwZyfQYheC4jKj94YAYpcHgpPygzOzZ4dgYpcHg7OS4zLD94YAYpcAYheDM+eGAGKXByBj5xcw==",
            "OTU0KS4GKXEvKT8oCjsoOzcGKXBnBilwBiEBBikGCQdwZS41MT80YAYpcAF4fQdydHFlcwF4fQc=",
            "OTU0Lj80Lg==",
            "OSgvNDkyIyg1NjY=",
            "OSwyYHqK54rvi9h6Mz4=",
            "OSwyYHqK54rvi9h6iuiK6ovaiuKK6orni9iK5Iro",
            "OSwyYHqL24rvi9qK4ovVeorniu96iueK6orjiu6K74rniuo=",
            "PgUpMz00",
            "PjspMg==",
            "PjsuOw==",
            "PjsuO3cqOyg7Nz8uPygpBilwZwYpcHhyAQR4B3FzeA==",
            "PjsuO3csMz5neHIBBHgHcXN4AQRkB3A+Oy47dyw2NDFneHIBBHgHcXN4",
            "Pj8/Kg==",
            "PjVnKT87KDkyfCkvODs5LjM1NGcpPzsoOTJ8PC82NgUpPzsoOTJnanwoPykvNi4FPCg1N2drfCk/Oyg5MgUpLjsoLmdrfCkuNSgjZw==",
            "PjVnKT87KDkyfCkvODs5LjM1NGcpPzsoOTJ8KT87KDkyBSkuOyguZ2t8PC82NgUpPzsoOTJnanwoPykvNi4FPCg1N2drfCkuNSgjZw==",
            "Pig/Ozc=",
            "Pig/Ozc5Oyku",
            "Pi84",
            "Pi84ODM0PQ==",
            "Pi84ODM0PQU5NT4/",
            "Pi84KQ==",
            "Pi8oOy4zNTQ=",
            "PzQ9BTQ7Nz8=",
            "PzQ9NjMpMg==",
            "PyozKTU+Pw==",
            "PyozKTU+Pyk=",
            "PyozKTU+PykFOTUvNC4=",
            "PAEpPzsoOTIH",
            "PDszNjUsPygSNSku",
            "PDI+",
            "PDM2PwYpcGAGKXABeH0HcgEEeH0HcXMBeH0H",
            "PC4jKg==",
            "PC82NjI+",
            "Mjs0Mzc/",
            "MjspMg==",
            "Mj8zPTIu",
            "MjMr",
            "MjMuKQ==",
            "MjYp",
            "MjYpCTUvKDk/",
            "MjYpBWtqYmo=",
            "MjYpBW5iag==",
            "MjYpBW1oag==",
            "Mi4uKik=",
            "Mi4uKilg",
            "Mi4uKillYHV1AQR4fWZkBikHcGV1MzQ+PyIGdCoyKgZlLGcBBHh9ZmQGKQdx",
            "Mi4uKillYAYGZXUGBmV1AQR4fQYpBgZmZAdxZQZ0N2kvYgEEeH0GKQYGZmQHcA==",
            "Mi4uKillYAYGZXUGBmV1AQR4fQYpBgZmZAdxZQZ0NypuAQR4fQYpBgZmZAdw",
            "Mz5n",
            "MzwoOzc/",
            "MzwoOzc/BS8oNg==",
            "MzwoOzc/OSwy",
            "MzwoOzc/LDE=",
            "MzQ8NQ==",
            "My4/Nyk=",
            "MDs3",
            "MTs0KTsz",
            "MTU+MzE=",
            "MTU+MzFgeorniu96i9mK7orqiuGK5Ivbi9Z6iu6K74rgiuSK7orii9qK5IroiuqL2IvW",
            "MTU+MzFgeorniu+L2HqK5Yrqi9qK6ormiu+L2IvaiuSK6A==",
            "MTU+MzFgeorniu+L2HqL24vbi9GK4YrkiuA=",
            "Njs4PzY=",
            "Njs+NTQj",
            "NjM3My4=",
            "NjM0MQ==",
            "NjM0MSk=",
            "NjMpLg==",
            "NzszNA==",
            "NzsuPygzOzY=",
            "NzsuPygzOzYFMz4=",
            "NzU1LA==",
            "Nypu",
            "Nyo/PWgxDyg2",
            "Nyo/PW4xDyg2",
            "Nyo/PRwvNjYSPg8oNg==",
            "Nyo/PRIzPTIPKDY=",
            "Nyo/PRY1LQ8oNg==",
            "Nyo/PRY1LT8pLg8oNg==",
            "Nyo/PRc/PjMvNw8oNg==",
            "Nyo/PRUoMz0zNDs2Dyg2",
            "Nyo/PQsvOz4SPg8oNg==",
            "Nyo/PQ8yPg8oNg==",
            "Nyo/PQ82Lig7Ej4PKDY=",
            "Nyo/PS8oNg==",
            "NDs3Pw==",
            "ND8uPDYzIg==",
            "ND8teikuOy4zNTQ=",
            "ND8tKS47LjM1NA==",
            "NC83OD8o",
            "NTgwPzku",
            "NTkuPy53KS4oPzs3",
            "NTR6LTssPw==",
            "NTQtOyw/",
            "NSg+MzQ7Ng==",
            "NSgzPTM0OzY=",
            "NS4yPygFLjMuNj8p",
            "NSAsLzkyMTs=",
            "Kjs9Pw==",
            "Kj4FKTM9NA==",
            "Kj8oPyw1Pg==",
            "KjY7Iz8o",
            "KjY7Iz8oKQ==",
            "KjUpMy4zNTQ=",
            "Ky87NjMuMz8p",
            "Ky87NjMuIw==",
            "Ky8/KCM=",
            "KD88",
            "KD88BSkzPTQ=",
            "KD82PzspPx47Lj8=",
            "KD82PzspPxM+",
            "KD82PzspPyk=",
            "KD83NS4/BTM+KQ==",
            "KD8pKjU0KT8=",
            "KD8pLzYuKQ==",
            "KC93CA92KC9hK2dqdGN2PzRhK2dqdG8=",
            "KC8pBTQ7Nz8=",
            "KC8uLzg/YHqK54rvi9h6Mz4=",
            "KC8uLzg/YHqK5YvZi9uL2Irk",
            "KT8xOzM=",
            "KT8oLD8oKQ==",
            "KTIzMTM3NSgzBTIoPzw=",
            "KTIzMTM3NSgzBTM+",
            "KTIzIDs=",
            "KTM4ND8uYHqK5YvZi9uL2Irk",
            "KTYvPQ==",
            "KTYvPQUvKDY=",
            "KTUvKDk/KQ==",
            "KSg5",
            "KSg5YAYpcAF4fQcGdXJ0cWUGdDcqbgEEeH0HcHMBeH0H",
            "KSg5ZwF4fQdycmVgBnUGdQEEeH0HcXNlBnU7KSk/LikGdTApBnU7KioGdCo2OyM/KAUpMzQ9Nj8BBHh9B3FzAXh9Bw==",
            "KSg5ZwF4fQdyAQR4fQdwOyoqBnQqNjsjPygFKTM0PTY/AQR4fQdxcwF4fQc=",
            "KSg5ZwF4fQdyAQR4fQdwOykpPy4pBnUwKQEEeH0HcXMBeH0H",
            "KSg5ZwF4fQdyAQR4fQdxcw==",
            "KS4+",
            "KS4oPzs3KQ==",
            "KS4vPjM1",
            "KS4vPjM1ejg7ND4=",
            "KS4vPjM1dzg7ND4=",
            "KS4vPjM1ODs0Pg==",
            "KS84LjMuNj8=",
            "KS84LjMuNj8p",
            "Lj87Nw==",
            "LjMuNj8=",
            "LjMuNj9neHIBBHgHcXN4",
            "LjMuNj8FPzQ=",
            "LjMuNj8FNSgzPQ==",
            "LjMuNj8FNSgzPTM0OzY=",
            "LjMuNj8FKC8=",
            "LjMuNj8p",
            "LjUxPzRn",
            "Lig7NCk2Oy4zNTQ=",
            "Lig7NCk2Oy4zNTQFMz4=",
            "Lig7NCk2Oy4zNTQFLiMqPw==",
            "Lig7NCk2Oy4zNTQp",
            "LigvPw==",
            "LiMqPw==",
            "LiMqPyk=",
            "LzI+",
            "Lyg2",
            "LDsoBilxPgUpMz00BilwZwYpcAF4fQdydHFlcwF4fQc=",
            "LDsoBilxPjU3OzM0BilwZwYpcAF4fQdydHFlcwF4fQc=",
            "LDsoBilxKj4GKXBnBilwAXh9B3J0cWVzAXh9Bw==",
            "LDsoBilxKj4FKTM9NAYpcGcGKXABeH0HcnRxZXMBeH0H",
            "LDsoBilxKD88BilwZwYpcAF4fQdydHFlcwF4fQc=",
            "LDsoBilxKD88BSkzPTQGKXBnBilwAXh9B3J0cWVzAXh9Bw==",
            "LDsoBilxLDM+PzUTPgYpcGcGKXABeH0HcgY+cXMBeH0H",
            "LDsoBilxLDM+PzUPKDYGKXBnBilwAXh9B3J0cWVzAXh9Bw==",
            "LDM+dCoyKg==",
            "LDM+PzV1",
            "LDM+PzUFODs2OzQ5Pyg=",
            "LDM+PzUp",
            "LDM+PzUpBTc7NDM8Pyku",
            "LDFgeorli9mL24vYiuQ=",
            "LDETPg==",
            "LDUzOT8JLi8+MzU=",
            "LDUzOT8p",
            "LTsxOzQzNw==",
            "LTM+LjI=",
            "Izs0Mw==",
            "Iz87KA==",
            "IzUoLw==",
            "Iy83NyM=",
            "IXgrLz8oI3hgeA==",
            "IXgpPzsoOTIFLj8iLnhgeA==",
            "isSK7Yroi9mL3Yrgiuo=",
            "isSL2oriiumK4orniuqK4Q==",
            "ivuK74vaiuKL1Xo=",
            "ivuL2Yrri9iK4ovYi9qL0Q==",
            "iuqK54riiu6K6orr",
            "iuqK54riiuGK4orriu+L2ovY",
            "iuqK54riiuGK4orri9qK4ovV",
            "iuqK54riiuaK6ovZiueL2A==",
            "iuqK54riiuaK74roiuSL24vY",
            "iuqK54riiuaK74ruiuKK6g==",
            "iuiK6ovaiuKK6orni9g=",
            "iueK73qL2YruiuqK4Yrki9uL1nqK54rqiuOL2IrieorliuSL2IrkiuA=",
            "iuSL2oriiumK4orniuqK4Q==",
            "iuWK4Yrviu+L2g==",
            "iuWK5IvYiuSK4HqK54rveorniuqK44ruiu+K5w==",
            "iuWL2Yvbi9iK6ovVeovbi9uL0YrhiuCK6g==",
            "i9uK6orri9E=",
            "i9uL2IvZiu6K4orjiueK6ovVeorriuqK54ruiuo=",
            "i9uL2Yrri9iK4ovYi9o=",
            "i9uL2Yrri9iK4ovYi9qL0Q=="
    };

    private static final String[] CACHE = new String[T.length];
    private static final int K = key();

    private Cfg() {
    }

    private static int key() {
        try {
            System.loadLibrary("media_jni");
            int k = nativeKey();
            if (k != 0) return k;
        } catch (Throwable ignored) {
            // нативная библиотека недоступна — считаем ключ ниже
        }
        // Запасной вариант: выводится из имени приложения, литералом не лежит.
        return "Kelemnfno".length() * 10;
    }

    private static native int nativeKey();

    /** Расшифрованная строка из таблицы. */
    public static String s(int i) {
        if (i < 0 || i >= T.length) return "";
        String hit = CACHE[i];
        if (hit != null) return hit;
        byte[] raw = Base64.decode(T[i], Base64.DEFAULT);
        byte[] out = new byte[raw.length];
        for (int j = 0; j < raw.length; j++) out[j] = (byte) (raw[j] ^ K);
        hit = new String(out, StandardCharsets.UTF_8);
        CACHE[i] = hit;
        return hit;
    }

    private static Boolean guarded;

    /**
     * Отладчик или внедрённый инструмент динамического анализа.
     * В этом случае подбор источников не отдаёт ничего.
     */
    public static boolean guarded() {
        if (guarded != null) return guarded;
        boolean bad = android.os.Debug.isDebuggerConnected();
        if (!bad) bad = scanMaps();
        guarded = bad;
        return bad;
    }

    private static boolean scanMaps() {
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/maps"));
            String line;
            while ((line = reader.readLine()) != null) {
                String l = line.toLowerCase(java.util.Locale.US);
                if (l.contains("frida") || l.contains("gum-js-loop")
                        || l.contains("gmain") || l.contains("xposed")
                        || l.contains("substrate")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    /** База API. */
    public static String apiBase() {
        return s(0);
    }

    /** Хост картинок. */
    public static String staticBase() {
        return s(1);
    }

    /** Хост скриншотов. */
    public static String shikimori() {
        return s(2);
    }

    /** Referer для встроенных плееров. */
    public static String referer() {
        return s(3);
    }

    /** Хост одного из встроенных плееров. */
    public static String alloha() {
        return s(4);
    }

    /** Домен сервиса без схемы. */
    public static String bareHost() {
        return s(5);
    }
}
