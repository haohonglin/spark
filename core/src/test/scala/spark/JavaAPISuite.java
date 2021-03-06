package spark;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import scala.Tuple2;

import spark.api.java.JavaDoubleRDD;
import spark.api.java.JavaPairRDD;
import spark.api.java.JavaRDD;
import spark.api.java.JavaSparkContext;
import spark.api.java.function.*;
import spark.partial.BoundedDouble;
import spark.partial.PartialResult;
import spark.storage.StorageLevel;
import spark.util.StatCounter;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;

// The test suite itself is Serializable so that anonymous Function implementations can be
// serialized, as an alternative to converting these anonymous classes to static inner classes;
// see http://stackoverflow.com/questions/758570/.
public class JavaAPISuite implements Serializable {
  private transient JavaSparkContext sc;

  @Before
  public void setUp() {
    sc = new JavaSparkContext("local", "JavaAPISuite");
  }

  @After
  public void tearDown() {
    sc.stop();
    sc = null;
    // To avoid Akka rebinding to the same port, since it doesn't unbind immediately on shutdown
    System.clearProperty("spark.master.port");
  }

  static class ReverseIntComparator implements Comparator<Integer>, Serializable {

    @Override
    public int compare(Integer a, Integer b) {
      if (a > b) return -1;
      else if (a < b) return 1;
      else return 0;
    }
  };

  @Test
  public void sparkContextUnion() {
    // Union of non-specialized JavaRDDs
    List<String> strings = Arrays.asList("Hello", "World");
    JavaRDD<String> s1 = sc.parallelize(strings);
    JavaRDD<String> s2 = sc.parallelize(strings);
    // Varargs
    JavaRDD<String> sUnion = sc.union(s1, s2);
    Assert.assertEquals(4, sUnion.count());
    // List
    List<JavaRDD<String>> list = new ArrayList<JavaRDD<String>>();
    list.add(s2);
    sUnion = sc.union(s1, list);
    Assert.assertEquals(4, sUnion.count());

    // Union of JavaDoubleRDDs
    List<Double> doubles = Arrays.asList(1.0, 2.0);
    JavaDoubleRDD d1 = sc.parallelizeDoubles(doubles);
    JavaDoubleRDD d2 = sc.parallelizeDoubles(doubles);
    JavaDoubleRDD dUnion = sc.union(d1, d2);
    Assert.assertEquals(4, dUnion.count());

    // Union of JavaPairRDDs
    List<Tuple2<Integer, Integer>> pairs = new ArrayList<Tuple2<Integer, Integer>>();
    pairs.add(new Tuple2<Integer, Integer>(1, 2));
    pairs.add(new Tuple2<Integer, Integer>(3, 4));
    JavaPairRDD<Integer, Integer> p1 = sc.parallelizePairs(pairs);
    JavaPairRDD<Integer, Integer> p2 = sc.parallelizePairs(pairs);
    JavaPairRDD<Integer, Integer> pUnion = sc.union(p1, p2);
    Assert.assertEquals(4, pUnion.count());
  }

  @Test
  public void sortByKey() {
    List<Tuple2<Integer, Integer>> pairs = new ArrayList<Tuple2<Integer, Integer>>();
    pairs.add(new Tuple2<Integer, Integer>(0, 4));
    pairs.add(new Tuple2<Integer, Integer>(3, 2));
    pairs.add(new Tuple2<Integer, Integer>(-1, 1));

    JavaPairRDD<Integer, Integer> rdd = sc.parallelizePairs(pairs);

    // Default comparator
    JavaPairRDD<Integer, Integer> sortedRDD = rdd.sortByKey();
    Assert.assertEquals(new Tuple2<Integer, Integer>(-1, 1), sortedRDD.first());
    List<Tuple2<Integer, Integer>> sortedPairs = sortedRDD.collect();
    Assert.assertEquals(new Tuple2<Integer, Integer>(0, 4), sortedPairs.get(1));
    Assert.assertEquals(new Tuple2<Integer, Integer>(3, 2), sortedPairs.get(2));

    // Custom comparator
    sortedRDD = rdd.sortByKey(new ReverseIntComparator(), false);
    Assert.assertEquals(new Tuple2<Integer, Integer>(-1, 1), sortedRDD.first());
    sortedPairs = sortedRDD.collect();
    Assert.assertEquals(new Tuple2<Integer, Integer>(0, 4), sortedPairs.get(1));
    Assert.assertEquals(new Tuple2<Integer, Integer>(3, 2), sortedPairs.get(2));
  }

  static int foreachCalls = 0;

  @Test
  public void foreach() {
    foreachCalls = 0;
    JavaRDD<String> rdd = sc.parallelize(Arrays.asList("Hello", "World"));
    rdd.foreach(new VoidFunction<String>() {
      @Override
      public void call(String s) {
        foreachCalls++;
      }
    });
    Assert.assertEquals(2, foreachCalls);
  }

  @Test
  public void groupBy() {
    JavaRDD<Integer> rdd = sc.parallelize(Arrays.asList(1, 1, 2, 3, 5, 8, 13));
    Function<Integer, Boolean> isOdd = new Function<Integer, Boolean>() {
      @Override
      public Boolean call(Integer x) {
        return x % 2 == 0;
      }
    };
    JavaPairRDD<Boolean, List<Integer>> oddsAndEvens = rdd.groupBy(isOdd);
    Assert.assertEquals(2, oddsAndEvens.count());
    Assert.assertEquals(2, oddsAndEvens.lookup(true).get(0).size());  // Evens
    Assert.assertEquals(5, oddsAndEvens.lookup(false).get(0).size()); // Odds

    oddsAndEvens = rdd.groupBy(isOdd, 1);
    Assert.assertEquals(2, oddsAndEvens.count());
    Assert.assertEquals(2, oddsAndEvens.lookup(true).get(0).size());  // Evens
    Assert.assertEquals(5, oddsAndEvens.lookup(false).get(0).size()); // Odds
  }

  @Test
  public void cogroup() {
    JavaPairRDD<String, String> categories = sc.parallelizePairs(Arrays.asList(
      new Tuple2<String, String>("Apples", "Fruit"),
      new Tuple2<String, String>("Oranges", "Fruit"),
      new Tuple2<String, String>("Oranges", "Citrus")
      ));
    JavaPairRDD<String, Integer> prices = sc.parallelizePairs(Arrays.asList(
      new Tuple2<String, Integer>("Oranges", 2),
      new Tuple2<String, Integer>("Apples", 3)
    ));
    JavaPairRDD<String, Tuple2<List<String>, List<Integer>>> cogrouped = categories.cogroup(prices);
    Assert.assertEquals("[Fruit, Citrus]", cogrouped.lookup("Oranges").get(0)._1().toString());
    Assert.assertEquals("[2]", cogrouped.lookup("Oranges").get(0)._2().toString());

    cogrouped.collect();
  }

  @Test
  public void foldReduce() {
    JavaRDD<Integer> rdd = sc.parallelize(Arrays.asList(1, 1, 2, 3, 5, 8, 13));
    Function2<Integer, Integer, Integer> add = new Function2<Integer, Integer, Integer>() {
      @Override
      public Integer call(Integer a, Integer b) {
        return a + b;
      }
    };

    int sum = rdd.fold(0, add);
    Assert.assertEquals(33, sum);

    sum = rdd.reduce(add);
    Assert.assertEquals(33, sum);
  }

  @Test
  public void reduceByKey() {
    List<Tuple2<Integer, Integer>> pairs = Arrays.asList(
      new Tuple2<Integer, Integer>(2, 1),
      new Tuple2<Integer, Integer>(2, 1),
      new Tuple2<Integer, Integer>(1, 1),
      new Tuple2<Integer, Integer>(3, 2),
      new Tuple2<Integer, Integer>(3, 1)
    );
    JavaPairRDD<Integer, Integer> rdd = sc.parallelizePairs(pairs);
    JavaPairRDD<Integer, Integer> counts = rdd.reduceByKey(
      new Function2<Integer, Integer, Integer>() {
        @Override
        public Integer call(Integer a, Integer b) {
         return a + b;
        }
    });
    Assert.assertEquals(1, counts.lookup(1).get(0).intValue());
    Assert.assertEquals(2, counts.lookup(2).get(0).intValue());
    Assert.assertEquals(3, counts.lookup(3).get(0).intValue());

    Map<Integer, Integer> localCounts = counts.collectAsMap();
    Assert.assertEquals(1, localCounts.get(1).intValue());
    Assert.assertEquals(2, localCounts.get(2).intValue());
    Assert.assertEquals(3, localCounts.get(3).intValue());

   localCounts = rdd.reduceByKeyLocally(new Function2<Integer, Integer,
      Integer>() {
      @Override
      public Integer call(Integer a, Integer b) {
        return a + b;
      }
    });
    Assert.assertEquals(1, localCounts.get(1).intValue());
    Assert.assertEquals(2, localCounts.get(2).intValue());
    Assert.assertEquals(3, localCounts.get(3).intValue());
  }

  @Test
  public void approximateResults() {
    JavaRDD<Integer> rdd = sc.parallelize(Arrays.asList(1, 1, 2, 3, 5, 8, 13));
    Map<Integer, Long> countsByValue = rdd.countByValue();
    Assert.assertEquals(2, countsByValue.get(1).longValue());
    Assert.assertEquals(1, countsByValue.get(13).longValue());

    PartialResult<Map<Integer, BoundedDouble>> approx = rdd.countByValueApprox(1);
    Map<Integer, BoundedDouble> finalValue = approx.getFinalValue();
    Assert.assertEquals(2.0, finalValue.get(1).mean(), 0.01);
    Assert.assertEquals(1.0, finalValue.get(13).mean(), 0.01);
  }

  @Test
  public void take() {
    JavaRDD<Integer> rdd = sc.parallelize(Arrays.asList(1, 1, 2, 3, 5, 8, 13));
    Assert.assertEquals(1, rdd.first().intValue());
    List<Integer> firstTwo = rdd.take(2);
    List<Integer> sample = rdd.takeSample(false, 2, 42);
  }

  @Test
  public void cartesian() {
    JavaDoubleRDD doubleRDD = sc.parallelizeDoubles(Arrays.asList(1.0, 1.0, 2.0, 3.0, 5.0, 8.0));
    JavaRDD<String> stringRDD = sc.parallelize(Arrays.asList("Hello", "World"));
    JavaPairRDD<String, Double> cartesian = stringRDD.cartesian(doubleRDD);
    Assert.assertEquals(new Tuple2<String, Double>("Hello", 1.0), cartesian.first());
  }

  @Test
  public void javaDoubleRDD() {
    JavaDoubleRDD rdd = sc.parallelizeDoubles(Arrays.asList(1.0, 1.0, 2.0, 3.0, 5.0, 8.0));
    JavaDoubleRDD distinct = rdd.distinct();
    Assert.assertEquals(5, distinct.count());
    JavaDoubleRDD filter = rdd.filter(new Function<Double, Boolean>() {
      @Override
      public Boolean call(Double x) {
        return x > 2.0;
      }
    });
    Assert.assertEquals(3, filter.count());
    JavaDoubleRDD union = rdd.union(rdd);
    Assert.assertEquals(12, union.count());
    union = union.cache();
    Assert.assertEquals(12, union.count());

    Assert.assertEquals(20, rdd.sum(), 0.01);
    StatCounter stats = rdd.stats();
    Assert.assertEquals(20, stats.sum(), 0.01);
    Assert.assertEquals(20/6.0, rdd.mean(), 0.01);
    Assert.assertEquals(20/6.0, rdd.mean(), 0.01);
    Assert.assertEquals(6.22222, rdd.variance(), 0.01);
    Assert.assertEquals(2.49444, rdd.stdev(), 0.01);

    Double first = rdd.first();
    List<Double> take = rdd.take(5);
  }

  @Test
  public void map() {
    JavaRDD<Integer> rdd = sc.parallelize(Arrays.asList(1, 2, 3, 4, 5));
    JavaDoubleRDD doubles = rdd.map(new DoubleFunction<Integer>() {
      @Override
      public Double call(Integer x) {
        return 1.0 * x;
      }
    }).cache();
    JavaPairRDD<Integer, Integer> pairs = rdd.map(new PairFunction<Integer, Integer, Integer>() {
      @Override
      public Tuple2<Integer, Integer> call(Integer x) {
        return new Tuple2<Integer, Integer>(x, x);
      }
    }).cache();
    JavaRDD<String> strings = rdd.map(new Function<Integer, String>() {
      @Override
      public String call(Integer x) {
        return x.toString();
      }
    }).cache();
  }

  @Test
  public void flatMap() {
    JavaRDD<String> rdd = sc.parallelize(Arrays.asList("Hello World!",
      "The quick brown fox jumps over the lazy dog."));
    JavaRDD<String> words = rdd.flatMap(new FlatMapFunction<String, String>() {
      @Override
      public Iterable<String> call(String x) {
        return Arrays.asList(x.split(" "));
      }
    });
    Assert.assertEquals("Hello", words.first());
    Assert.assertEquals(11, words.count());

    JavaPairRDD<String, String> pairs = rdd.flatMap(
      new PairFlatMapFunction<String, String, String>() {

        @Override
        public Iterable<Tuple2<String, String>> call(String s) {
          List<Tuple2<String, String>> pairs = new LinkedList<Tuple2<String, String>>();
          for (String word : s.split(" ")) pairs.add(new Tuple2<String, String>(word, word));
          return pairs;
        }
      }
    );
    Assert.assertEquals(new Tuple2<String, String>("Hello", "Hello"), pairs.first());
    Assert.assertEquals(11, pairs.count());

    JavaDoubleRDD doubles = rdd.flatMap(new DoubleFlatMapFunction<String>() {
      @Override
      public Iterable<Double> call(String s) {
        List<Double> lengths = new LinkedList<Double>();
        for (String word : s.split(" ")) lengths.add(word.length() * 1.0);
        return lengths;
      }
    });
    Double x = doubles.first();
    Assert.assertEquals(5.0, doubles.first().doubleValue(), 0.01);
    Assert.assertEquals(11, pairs.count());
  }

  @Test
  public void mapPartitions() {
    JavaRDD<Integer> rdd = sc.parallelize(Arrays.asList(1, 2, 3, 4), 2);
    JavaRDD<Integer> partitionSums = rdd.mapPartitions(
      new FlatMapFunction<Iterator<Integer>, Integer>() {
        @Override
        public Iterable<Integer> call(Iterator<Integer> iter) {
          int sum = 0;
          while (iter.hasNext()) {
            sum += iter.next();
          }
          return Collections.singletonList(sum);
        }
    });
    Assert.assertEquals("[3, 7]", partitionSums.collect().toString());
  }

  @Test
  public void persist() {
    JavaDoubleRDD doubleRDD = sc.parallelizeDoubles(Arrays.asList(1.0, 1.0, 2.0, 3.0, 5.0, 8.0));
    doubleRDD = doubleRDD.persist(StorageLevel.DISK_ONLY());
    Assert.assertEquals(20, doubleRDD.sum(), 0.1);

    List<Tuple2<Integer, String>> pairs = Arrays.asList(
      new Tuple2<Integer, String>(1, "a"),
      new Tuple2<Integer, String>(2, "aa"),
      new Tuple2<Integer, String>(3, "aaa")
    );
    JavaPairRDD<Integer, String> pairRDD = sc.parallelizePairs(pairs);
    pairRDD = pairRDD.persist(StorageLevel.DISK_ONLY());
    Assert.assertEquals("a", pairRDD.first()._2());

    JavaRDD<Integer> rdd = sc.parallelize(Arrays.asList(1, 2, 3, 4, 5));
    rdd = rdd.persist(StorageLevel.DISK_ONLY());
    Assert.assertEquals(1, rdd.first().intValue());
  }

  @Test
  public void iterator() {
    JavaRDD<Integer> rdd = sc.parallelize(Arrays.asList(1, 2, 3, 4, 5), 2);
    Assert.assertEquals(1, rdd.iterator(rdd.splits().get(0)).next().intValue());
  }

  @Test
  public void glom() {
    JavaRDD<Integer> rdd = sc.parallelize(Arrays.asList(1, 2, 3, 4), 2);
    Assert.assertEquals("[1, 2]", rdd.glom().first().toString());
  }

  // File input / output tests are largely adapted from FileSuite:

  @Test
  public void textFiles() throws IOException {
    File tempDir = Files.createTempDir();
    String outputDir = new File(tempDir, "output").getAbsolutePath();
    JavaRDD<Integer> rdd = sc.parallelize(Arrays.asList(1, 2, 3, 4));
    rdd.saveAsTextFile(outputDir);
    // Read the plain text file and check it's OK
    File outputFile = new File(outputDir, "part-00000");
    String content = Files.toString(outputFile, Charsets.UTF_8);
    Assert.assertEquals("1\n2\n3\n4\n", content);
    // Also try reading it in as a text file RDD
    List<String> expected = Arrays.asList("1", "2", "3", "4");
    JavaRDD<String> readRDD = sc.textFile(outputDir);
    Assert.assertEquals(expected, readRDD.collect());
  }

  @Test
  public void sequenceFile() {
    File tempDir = Files.createTempDir();
    String outputDir = new File(tempDir, "output").getAbsolutePath();
    List<Tuple2<Integer, String>> pairs = Arrays.asList(
      new Tuple2<Integer, String>(1, "a"),
      new Tuple2<Integer, String>(2, "aa"),
      new Tuple2<Integer, String>(3, "aaa")
    );
    JavaPairRDD<Integer, String> rdd = sc.parallelizePairs(pairs);

    rdd.map(new PairFunction<Tuple2<Integer, String>, IntWritable, Text>() {
      @Override
      public Tuple2<IntWritable, Text> call(Tuple2<Integer, String> pair) {
        return new Tuple2<IntWritable, Text>(new IntWritable(pair._1()), new Text(pair._2()));
      }
    }).saveAsHadoopFile(outputDir, IntWritable.class, Text.class, SequenceFileOutputFormat.class);

    // Try reading the output back as an object file
    JavaPairRDD<Integer, String> readRDD = sc.sequenceFile(outputDir, IntWritable.class,
      Text.class).map(new PairFunction<Tuple2<IntWritable, Text>, Integer, String>() {
      @Override
      public Tuple2<Integer, String> call(Tuple2<IntWritable, Text> pair) {
        return new Tuple2<Integer, String>(pair._1().get(), pair._2().toString());
      }
    });
    Assert.assertEquals(pairs, readRDD.collect());
  }

  @Test
  public void writeWithNewAPIHadoopFile() {
    File tempDir = Files.createTempDir();
    String outputDir = new File(tempDir, "output").getAbsolutePath();
    List<Tuple2<Integer, String>> pairs = Arrays.asList(
      new Tuple2<Integer, String>(1, "a"),
      new Tuple2<Integer, String>(2, "aa"),
      new Tuple2<Integer, String>(3, "aaa")
    );
    JavaPairRDD<Integer, String> rdd = sc.parallelizePairs(pairs);

    rdd.map(new PairFunction<Tuple2<Integer, String>, IntWritable, Text>() {
      @Override
      public Tuple2<IntWritable, Text> call(Tuple2<Integer, String> pair) {
        return new Tuple2<IntWritable, Text>(new IntWritable(pair._1()), new Text(pair._2()));
      }
    }).saveAsNewAPIHadoopFile(outputDir, IntWritable.class, Text.class,
      org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat.class);

    JavaPairRDD<IntWritable, Text> output = sc.sequenceFile(outputDir, IntWritable.class,
      Text.class);
    Assert.assertEquals(pairs.toString(), output.map(new Function<Tuple2<IntWritable, Text>,
      String>() {
      @Override
      public String call(Tuple2<IntWritable, Text> x) {
        return x.toString();
      }
    }).collect().toString());
  }

  @Test
  public void readWithNewAPIHadoopFile() throws IOException {
    File tempDir = Files.createTempDir();
    String outputDir = new File(tempDir, "output").getAbsolutePath();
    List<Tuple2<Integer, String>> pairs = Arrays.asList(
      new Tuple2<Integer, String>(1, "a"),
      new Tuple2<Integer, String>(2, "aa"),
      new Tuple2<Integer, String>(3, "aaa")
    );
    JavaPairRDD<Integer, String> rdd = sc.parallelizePairs(pairs);

    rdd.map(new PairFunction<Tuple2<Integer, String>, IntWritable, Text>() {
      @Override
      public Tuple2<IntWritable, Text> call(Tuple2<Integer, String> pair) {
        return new Tuple2<IntWritable, Text>(new IntWritable(pair._1()), new Text(pair._2()));
      }
    }).saveAsHadoopFile(outputDir, IntWritable.class, Text.class, SequenceFileOutputFormat.class);

    JavaPairRDD<IntWritable, Text> output = sc.newAPIHadoopFile(outputDir,
      org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat.class, IntWritable.class,
      Text.class, new Job().getConfiguration());
    Assert.assertEquals(pairs.toString(), output.map(new Function<Tuple2<IntWritable, Text>,
      String>() {
      @Override
      public String call(Tuple2<IntWritable, Text> x) {
        return x.toString();
      }
    }).collect().toString());
  }

  @Test
  public void objectFilesOfInts() {
    File tempDir = Files.createTempDir();
    String outputDir = new File(tempDir, "output").getAbsolutePath();
    JavaRDD<Integer> rdd = sc.parallelize(Arrays.asList(1, 2, 3, 4));
    rdd.saveAsObjectFile(outputDir);
    // Try reading the output back as an object file
    List<Integer> expected = Arrays.asList(1, 2, 3, 4);
    JavaRDD<Integer> readRDD = sc.objectFile(outputDir);
    Assert.assertEquals(expected, readRDD.collect());
  }

  @Test
  public void objectFilesOfComplexTypes() {
    File tempDir = Files.createTempDir();
    String outputDir = new File(tempDir, "output").getAbsolutePath();
    List<Tuple2<Integer, String>> pairs = Arrays.asList(
      new Tuple2<Integer, String>(1, "a"),
      new Tuple2<Integer, String>(2, "aa"),
      new Tuple2<Integer, String>(3, "aaa")
    );
    JavaPairRDD<Integer, String> rdd = sc.parallelizePairs(pairs);
    rdd.saveAsObjectFile(outputDir);
    // Try reading the output back as an object file
    JavaRDD<Tuple2<Integer, String>> readRDD = sc.objectFile(outputDir);
    Assert.assertEquals(pairs, readRDD.collect());
  }

  @Test
  public void hadoopFile() {
    File tempDir = Files.createTempDir();
    String outputDir = new File(tempDir, "output").getAbsolutePath();
    List<Tuple2<Integer, String>> pairs = Arrays.asList(
      new Tuple2<Integer, String>(1, "a"),
      new Tuple2<Integer, String>(2, "aa"),
      new Tuple2<Integer, String>(3, "aaa")
    );
    JavaPairRDD<Integer, String> rdd = sc.parallelizePairs(pairs);

    rdd.map(new PairFunction<Tuple2<Integer, String>, IntWritable, Text>() {
      @Override
      public Tuple2<IntWritable, Text> call(Tuple2<Integer, String> pair) {
        return new Tuple2<IntWritable, Text>(new IntWritable(pair._1()), new Text(pair._2()));
      }
    }).saveAsHadoopFile(outputDir, IntWritable.class, Text.class, SequenceFileOutputFormat.class);

    JavaPairRDD<IntWritable, Text> output = sc.hadoopFile(outputDir,
      SequenceFileInputFormat.class, IntWritable.class, Text.class);
    Assert.assertEquals(pairs.toString(), output.map(new Function<Tuple2<IntWritable, Text>,
      String>() {
      @Override
      public String call(Tuple2<IntWritable, Text> x) {
        return x.toString();
      }
    }).collect().toString());
  }

  @Test
  public void zip() {
    JavaRDD<Integer> rdd = sc.parallelize(Arrays.asList(1, 2, 3, 4, 5));
    JavaDoubleRDD doubles = rdd.map(new DoubleFunction<Integer>() {
      @Override
      public Double call(Integer x) {
        return 1.0 * x;
      }
    });
    JavaPairRDD<Integer, Double> zipped = rdd.zip(doubles);
    zipped.count();
  }
}
