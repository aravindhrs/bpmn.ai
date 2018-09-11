package de.viadee.ki.sparkimporter.processing.steps.dataprocessing;

import de.viadee.ki.sparkimporter.processing.interfaces.PreprocessingStepInterface;
import de.viadee.ki.sparkimporter.util.SparkImporterVariables;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.encoders.RowEncoder;
import org.apache.spark.sql.expressions.Window;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

import static org.apache.spark.sql.functions.*;

public class MatchBrandsStep implements PreprocessingStepInterface {

	@Override
	public Dataset<Row> runPreprocessingStep(Dataset<Row> dataset, boolean writeStepResultIntoFile, String dataLevel) {

		final SparkSession sparkSession = SparkSession.builder().getOrCreate();

		String herstellercolumn = "int_fahrzeugHerstellernameAusVertrag";

		Dataset<Row> levenshteinds = LevenshteinMatching(dataset, sparkSession, herstellercolumn);
		Dataset<Row> matchedds = regexMatching(levenshteinds, sparkSession, herstellercolumn);

		return matchedds;
	}

	// Perform similarity matching of the brands using the levenshtein score
	public static Dataset<Row> LevenshteinMatching(Dataset<Row> ds, SparkSession s, String herstellercolumn) {

		// read brands
		Dataset<Row> brands = s.read().option("header", "false").option("delimiter", ",")
				.csv("C:\\Users\\B77\\Desktop\\Glasbruch-Mining\\car_brands.csv");

		// compare all
		Dataset<Row> joined = ds.crossJoin(brands);

		// remove all special characters and convert to upper case
		joined = joined.withColumn(herstellercolumn, upper(joined.col(herstellercolumn)));
		joined = joined.withColumn("int_fahrzeugHerstellernameAusVertragModified", regexp_replace(
				joined.col(herstellercolumn), "[\\-,1,2,3,4,5,6,7,8,9,0,\\.,\\,\\_,\\+,\\),\\(,/\\s/g]", ""));
		joined = joined.withColumn("_c1_Modified",
				regexp_replace(joined.col("_c1"), "[\\-,1,2,3,4,5,6,7,8,9,0,\\.,\\,\\_,\\+,\\),\\(,/\\s/g]", ""));

		// calculate score
		joined = joined.withColumn("score",
				levenshtein(joined.col("int_fahrzeugHerstellernameAusVertragModified"), joined.col("_c1_Modified")));
		joined = joined.withColumn("maxLength",
				(when(length(joined.col("_c1_Modified"))
						.lt(length(joined.col("int_fahrzeugHerstellernameAusVertragModified"))), length(joined.col("int_fahrzeugHerstellernameAusVertragModified")))
						.otherwise(length(joined.col("_c1_Modified")))));
		joined = joined.withColumn("ratio", joined.col("score").divide(joined.col("maxLength")));

		// filter rows with minimal ratios
		org.apache.spark.sql.expressions.WindowSpec windowSpec =
				Window.partitionBy(joined.col(SparkImporterVariables.VAR_PROCESS_INSTANCE_ID))
					.orderBy(joined.col("ratio").asc());
		joined = joined.withColumn("minRatio", first(joined.col("ratio")).over(windowSpec).as("minRatio"))
				.filter("ratio = minRatio");
		joined = joined.drop("ratio").drop("_c0");
		joined = joined.withColumn("brand",
				when(joined.col("minRatio").lt(0.5), joined.col("_c1")).otherwise("Sonstige"));
		joined = joined.orderBy(asc(SparkImporterVariables.VAR_PROCESS_INSTANCE_ID));
		joined = joined.dropDuplicates(SparkImporterVariables.VAR_PROCESS_INSTANCE_ID);

		return joined;
	}

	// applies the regexp functions saved in a csv file to the brands of the dataset
	public static Dataset<Row> regexMatching(Dataset<Row> dataset, SparkSession s, String herstellercolumn) {

		// read matching data in a 2-dim array
		String fileName = "C:\\Users\\B77\\Desktop\\brandmatching.csv";
		File file = new File(fileName);

		// return a 2-dimensional array of strings
		List<List<String>> brandsRegexp = new ArrayList<>();
		Scanner inputStream;
		try {
			inputStream = new Scanner(file);
			while (inputStream.hasNext()) {
				String line = inputStream.next();
				String[] values = line.split(";");
				brandsRegexp.add(Arrays.asList(values));
			}
			inputStream.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}

		String[] columns = dataset.columns();

		// traverse the dataset
		dataset = dataset.map(row -> {

			Object[] newRow = new Object[columns.length];
			int columnCount = 0;
			for (String c : columns) {
				Object columnValue = null;

				// if brand is not matched
				if (c.equals("brand") && row.getAs(c).equals("Sonstige")) {

					String regexpValue = null;
					int lineNo = 1;

					// traverse the matching list
					for (List<String> line : brandsRegexp) {
						int columnNo = 1;
						String brandMatch = line.get(0);
						String regExpBrand = line.get(1);
						lineNo++;

						// replace value with regexp from matching list
						columnValue = ((String) row.getAs(herstellercolumn)).replaceAll(regExpBrand, brandMatch);

						// stop loop if value is already replaced and otherwise the value stays
						// "Sonstige"
						if ((String) row.getAs(herstellercolumn) != columnValue) {
							break;
						} else {
							columnValue = "Sonstige";
						}
					}
				}
				// the value of all the other columns stay the same
				else {
					columnValue = row.getAs(c);
				}
				newRow[columnCount++] = columnValue;
			}

			return RowFactory.create(newRow);
		}, RowEncoder.apply(dataset.schema()));

		dataset = dataset.withColumn(herstellercolumn, dataset.col("brand"));
		
		dataset = dataset.drop("_c1").drop("score").drop("maxLength").drop("_c1_Modified").drop("minRatio").drop("id")
				.drop("int_fahrzeugHerstellernameAusVertragModified").drop("brand");
		
		

		/*
		 * save dataset into CSV file dataset.coalesce(1) .write() .option("header",
		 * "true") .option("delimiter", ";") .option("ignoreLeadingWhiteSpace", "false")
		 * .option("ignoreTrailingWhiteSpace", "false") .mode(SaveMode.Overwrite)
		 * .csv("C:\\Users\\B77\\Desktop\\outputBrands.csv");
		 */

		return dataset;
	}

}
