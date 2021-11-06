## Dependencies
```bash
1. java_version: 1.8
2. Gradle: 7.1
```

## QuickStart
```bash
sh run.sh
```

## Main Logic
#### 1. According to K-line data, we can simulate the price information of a specified block;
#### 2. Based on the swap records in Uniswap V3 pools, the signals calculated by strategies or offered by user, we can simulate the procedure of adding/removing liquidity ;
#### 3. Recording all these data and the initial cost specified by user, we can figure out the performance of strategy above.